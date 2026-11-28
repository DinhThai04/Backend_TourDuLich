param(
    [ValidateSet('all','gw-auth','user-role','tour-location','pricing-departure','booking','change','payment-invoice','search-file-ai')]
    [string]$Group = 'all',
    [ValidateSet('All','P0','P1','P2')]
    [string]$Severity = 'All',
    [switch]$StopOnFail,
    [switch]$IncludeRateLimit,
    [switch]$IncludeInfra
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
Add-Type -AssemblyName System.Web
Add-Type -AssemblyName System.Net.Http

$here        = Split-Path -Parent $MyInvocation.MyCommand.Path
$logDir      = Join-Path $here 'logs'
$statePath   = Join-Path $logDir 'err-state.properties'
$happyState  = Join-Path $logDir 'state.properties'
$gateway     = 'http://localhost:8888/api/v1'
$script:Http = [System.Net.Http.HttpClient]::new()
$script:Http.Timeout = [TimeSpan]::FromSeconds(45)
$script:ResultsByGroup = @{}
$script:Counts = @{ Pass = 0; Fail = 0; Skip = 0 }

# ---------------- state ----------------
$script:State = @{}
function Import-StateFile([string]$path) {
    if (-not (Test-Path $path)) { return }
    foreach ($line in Get-Content $path) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $idx = $line.IndexOf('=')
        if ($idx -lt 1) { continue }
        $key = $line.Substring(0, $idx)
        if (-not $script:State.ContainsKey($key) -or [string]::IsNullOrWhiteSpace([string]$script:State[$key])) {
            $script:State[$key] = $line.Substring($idx + 1)
        }
    }
}
Import-StateFile $happyState
Import-StateFile $statePath

function Save-State {
    $lines = $script:State.Keys | Sort-Object | ForEach-Object { "$_=$($script:State[$_])" }
    [IO.File]::WriteAllText($statePath, ($lines -join "`r`n"), [System.Text.UTF8Encoding]::new($false))
}

# ---------------- HTTP core ----------------
function Invoke-Http {
    param(
        [string]$Method,
        [string]$Path,
        $Body = $null,
        [string]$RawBody = $null,
        [string]$ContentType = 'application/json',
        [hashtable]$Form = $null,
        [hashtable]$Files = $null,
        [string]$Token = $null,
        [string]$Base = $gateway,
        [hashtable]$Headers = $null,
        [switch]$Redact
    )
    $url = if ($Path -match '^https?://') { $Path } else { $Base + $Path }
    $req = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), [uri]$url)
    if ($Token) {
        $req.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $Token)
    }
    if ($Headers) {
        foreach ($hk in $Headers.Keys) {
            $hv = $Headers[$hk]
            if ($hv -is [array]) {
                foreach ($item in $hv) { $null = $req.Headers.TryAddWithoutValidation($hk, [string]$item) }
            }
            else {
                $null = $req.Headers.TryAddWithoutValidation($hk, [string]$hv)
            }
        }
    }
    $reqText = ''
    if ($Form -or $Files) {
        $mp = [System.Net.Http.MultipartFormDataContent]::new()
        if ($Form) {
            foreach ($k in $Form.Keys) {
                $part = [System.Net.Http.StringContent]::new([string]$Form[$k], [System.Text.Encoding]::UTF8)
                $null = $part.Headers.Remove('Content-Type')
                $mp.Add($part, $k)
            }
            $reqText = ($Form.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" } | Sort-Object) -join '& '
        }
        if ($Files) {
            foreach ($k in $Files.Keys) {
                foreach ($spec in @($Files[$k])) {
                    $bytes = [byte[]]$spec.Bytes
                    $part = [System.Net.Http.ByteArrayContent]::new($bytes)
                    $part.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse($spec.ContentType)
                    $mp.Add($part, $k, $spec.FileName)
                    $reqText += " file:$k=$($spec.FileName)($($bytes.Length)b)"
                }
            }
        }
        $req.Content = $mp
    }
    elseif (-not [string]::IsNullOrEmpty($RawBody)) {
        $req.Content = [System.Net.Http.StringContent]::new($RawBody, [System.Text.Encoding]::UTF8, $ContentType)
        $reqText = $RawBody
    }
    elseif ($null -ne $Body) {
        $json = if ($Body -is [string]) { $Body } else { $Body | ConvertTo-Json -Depth 20 -Compress }
        $req.Content = [System.Net.Http.StringContent]::new($json, [System.Text.Encoding]::UTF8, $ContentType)
        $reqText = $json
    }
    if ($Redact) {
        $reqText = [regex]::Replace($reqText, '(?i)(password|oldPassword|newPassword|confirmPassword|token|refreshToken)"?\s*[:=]\s*"?[^,}&\s"]+', '$1=***')
    }

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $status = -1
    $text = ''
    $ctype = ''
    $err = ''
    $respHeaders = @{}
    try {
        $resp = $script:Http.SendAsync($req).GetAwaiter().GetResult()
        $bytes = $resp.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
        $text = [System.Text.Encoding]::UTF8.GetString($bytes)
        $status = [int]$resp.StatusCode
        if ($resp.Content.Headers.ContentType) { $ctype = $resp.Content.Headers.ContentType.ToString() }
        foreach ($h in $resp.Headers) { $respHeaders[$h.Key] = ($h.Value -join ',') }
        $resp.Dispose()
    }
    catch {
        $err = $_.Exception.GetBaseException().Message
        $text = "TRANSPORT ERROR: $err"
    }
    $sw.Stop()

    $json = $null
    $trim = $text.TrimStart()
    if ($trim.StartsWith('{') -or $trim.StartsWith('[')) {
        try { $json = $text | ConvertFrom-Json } catch { $json = $null }
    }
    [pscustomobject]@{
        Status   = $status
        Text     = $text
        Json     = $json
        Ctype    = $ctype
        Error    = $err
        Headers  = $respHeaders
        Request  = $reqText
        ElapsedMs = [int]$sw.ElapsedMilliseconds
    }
}

function Add-Result {
    param($Record)
    if (-not $script:ResultsByGroup.ContainsKey($Record.Group)) {
        $script:ResultsByGroup[$Record.Group] = [System.Collections.Generic.List[object]]::new()
    }
    $script:ResultsByGroup[$Record.Group].Add($Record)
    switch ($Record.State) {
        'PASS' { $script:Counts.Pass++ }
        'FAIL' { $script:Counts.Fail++ }
        'SKIP' { $script:Counts.Skip++ }
    }
}

function Invoke-Case {
    param(
        [string]$Group,
        [string]$Id,
        [string]$Name,
        [string]$Severity = '',
        [string]$Method = 'GET',
        [string]$Path,
        $Body = $null,
        [string]$RawBody = $null,
        [string]$ContentType = 'application/json',
        [hashtable]$Form = $null,
        [hashtable]$Files = $null,
        [string]$Token = $null,
        [hashtable]$Headers = $null,
        [int[]]$ExpectStatus = @(200, 201, 204),
        [int[]]$ExpectCode = @(),
        [string]$ExpectErrorType = '',
        [string]$ExpectMessageLike = '',
        [string]$ExpectTextLike = '',
        [string]$CodePath = 'code',
        [string]$Base = $gateway,
        [string]$SkipReason = '',
        [string]$Note = '',
        [scriptblock]$Validate = $null,
        [switch]$Redact
    )

    if ($Severity -ne '' -and $Severity -ne $script:SeverityFilter -and $script:SeverityFilter -ne 'All') {
        return
    }

    if (-not [string]::IsNullOrWhiteSpace($SkipReason)) {
        $rec = [pscustomobject]@{
            Group = $Group; Id = $Id; Name = $Name; Severity = $Severity; Method = $Method; Path = $Path
            Status = ''; ExpectedStatus = ($ExpectStatus -join '/'); ExpectedCode = ($ExpectCode -join '/')
            ActualCode = ''; State = 'SKIP'; ElapsedMs = 0; Request = ''; Response = $SkipReason; Note = $Note
            Time = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
        }
        Add-Result $rec
        Write-Host ("[SKIP] {0,-10} {1,-48} {2}" -f $Id, $Name, $SkipReason)
        return $rec
    }

    $resp = Invoke-Http -Method $Method -Path $Path -Body $Body -RawBody $RawBody -ContentType $ContentType `
        -Form $Form -Files $Files -Token $Token -Base $Base -Headers $Headers -Redact:$Redact

    $actualCode = $null
    if ($null -ne $resp.Json) {
        try {
            $prop = $resp.Json.PSObject.Properties[$CodePath]
            if ($null -ne $prop) { $actualCode = $prop.Value }
        } catch { $actualCode = $null }
    }
    $actualType = ''
    if ($null -ne $resp.Json -and $null -ne $resp.Json.error -and $null -ne $resp.Json.error.type) {
        $actualType = [string]$resp.Json.error.type
    }

    $problems = @()
    if (-not ($ExpectStatus -contains $resp.Status)) {
        $problems += "status $($resp.Status) not in $($ExpectStatus -join '/')"
    }
    if ($ExpectCode.Count -gt 0) {
        $codeText = if ($null -eq $actualCode) { '<missing>' } else { [string]$actualCode }
        if (-not ($ExpectCode -contains [int]$actualCode)) {
            $problems += "code $codeText not in $($ExpectCode -join '/')"
        }
    }
    if (-not [string]::IsNullOrWhiteSpace($ExpectErrorType) -and $actualType -ne $ExpectErrorType) {
        $problems += "error.type '$actualType' != '$ExpectErrorType'"
    }
    if (-not [string]::IsNullOrWhiteSpace($ExpectMessageLike)) {
        $msg = ''
        if ($null -ne $resp.Json -and $null -ne $resp.Json.message) { $msg = [string]$resp.Json.message }
        if ($msg -notlike $ExpectMessageLike) { $problems += "message '$msg' !like '$ExpectMessageLike'" }
    }
    if (-not [string]::IsNullOrWhiteSpace($ExpectTextLike) -and $resp.Text -notlike $ExpectTextLike) {
        $problems += "text !like '$ExpectTextLike'"
    }

    $rec = [pscustomobject]@{
        Group = $Group; Id = $Id; Name = $Name; Severity = $Severity; Method = $Method; Path = $Path
        Status = $resp.Status; ExpectedStatus = ($ExpectStatus -join '/'); ExpectedCode = ($ExpectCode -join '/')
        ActualCode = $actualCode; State = ''; ElapsedMs = $resp.ElapsedMs; Request = $resp.Request
        Response = $(if ($resp.Text.Length -gt 4000) { $resp.Text.Substring(0, 4000) + '...[truncated]' } else { $resp.Text })
        Note = $Note; Time = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
    }

    if ($problems.Count -eq 0 -and $null -ne $Validate) {
        try {
            $valid = & $Validate $rec $resp
            if ($valid -eq $false) { $problems += 'custom validation returned false' }
            elseif ($valid -is [string] -and $valid.Length -gt 0) { $problems += $valid }
        } catch {
            $problems += "validation error: $($_.Exception.Message)"
        }
    }

    if ($problems.Count -eq 0) {
        $rec.State = 'PASS'
        Write-Host ("[PASS] {0,-10} {1,-48} {2,-6} {3,3} {4,5}ms" -f $Id, $Name, $Method, $resp.Status, $resp.ElapsedMs)
    }
    else {
        $rec.State = 'FAIL'
        $preview = ($resp.Text -replace "`r?`n", ' ')
        if ($preview.Length -gt 500) { $preview = $preview.Substring(0, 500) }
        Write-Host ("[FAIL] {0,-10} {1,-48} {2,-6} {3,3} {4,5}ms  {5}" -f $Id, $Name, $Method, $resp.Status, $resp.ElapsedMs, ($problems -join '; '))
        Write-Host ("       body: {0}" -f $preview)
        if ($StopOnFail) { throw "Test failed: $Id $Name" }
    }
    Add-Result $rec
    return $rec
}

function Invoke-Silent {
    param([string]$Method, [string]$Path, $Body = $null, [string]$Token = $null)
    $r = Invoke-Http -Method $Method -Path $Path -Body $Body -Token $Token
    return $r
}

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "ASSERTION FAILED: $Message" }
}

function New-Uuid { [guid]::NewGuid().ToString() }

# ---------------- login / fixtures ----------------
function Login-Silent {
    param([string]$Email, [string]$Password)
    $r = Invoke-Silent -Method POST -Path '/auth/users/login' -Body @{ email = $Email; password = $Password }
    if ($r.Status -eq 200 -and $null -ne $r.Json -and $null -ne $r.Json.result) { return $r.Json.result }
    return $null
}

function Ensure-Tokens {
    $admin = Login-Silent 'admin@example.com' 'admin123'
    $u1 = Login-Silent 'user1@gmail.com' 'user12345'
    $u5 = Login-Silent 'user5@gmail.com' 'user12345'
    if ($null -eq $admin -or $null -eq $u1 -or $null -eq $u5) {
        throw 'Cannot login seed accounts. Start MySQL :3307, the services and the gateway first.'
    }
    $script:State['adminToken'] = $admin.token
    $script:State['adminRefresh'] = $admin.refreshToken
    $script:State['user1Token'] = $u1.token
    $script:State['user5Token'] = $u5.token
    $script:State['adminId'] = [string]$admin.user.id
    $script:State['user1Id'] = [string]$u1.user.id
    $script:State['user5Id'] = [string]$u5.user.id
    Save-State
}

function New-FileSpec {
    param([string]$FileName, [int]$Size, [string]$ContentType = 'image/png', [byte[]]$Bytes = $null)
    if ($null -eq $Bytes) {
        $Bytes = [byte[]]::new($Size)
        for ($i = 0; $i -lt $Size; $i++) { $Bytes[$i] = [byte](65 + ($i % 26)) }
    }
    elseif ($Size -gt $Bytes.Length) {
        # Pad the given content up to Size so size-limit tests send a payload of the intended length.
        $padded = [byte[]]::new($Size)
        [Array]::Copy($Bytes, $padded, $Bytes.Length)
        $Bytes = $padded
    }
    [pscustomobject]@{ FileName = $FileName; Bytes = $Bytes; ContentType = $ContentType }
}

function New-PngBytes {
    param([int]$Width = 2, [int]$Height = 2)
    Add-Type -AssemblyName System.Drawing
    $bmp = [System.Drawing.Bitmap]::new($Width, $Height)
    $gfx = [System.Drawing.Graphics]::FromImage($bmp)
    $gfx.Clear([System.Drawing.Color]::CornflowerBlue)
    $gfx.Dispose()
    $ms = [System.IO.MemoryStream]::new()
    $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    return $ms.ToArray()
}

function Get-JsonResult {
    param([string]$Path, [string]$Token = $null, [string]$Base = $gateway)
    $r = Invoke-Silent -Method GET -Path $Path -Token $Token
    if ($r.Status -eq 200 -and $null -ne $r.Json) { return $r.Json.result }
    return $null
}

function Resolve-Fixtures {
    $script:Fixtures = @{}
    $script:Fixtures['futureDep'] = $null
    $script:Fixtures['secondDep'] = $null
    $script:Fixtures['ownBooking'] = $null
    $script:Fixtures['otherBooking'] = $null
    $script:Fixtures['changeId'] = $null
    $script:Fixtures['txnRef'] = $null
    $script:Fixtures['convId'] = $null

    # Pick a future departure for tour 1 with seats left.
    $deps = Get-JsonResult -Path '/tour/tour-departures/tour/1'
    if ($null -ne $deps) {
        $future = @($deps) | Where-Object {
            $_.departureDate -and ([datetime]$_.departureDate) -gt (Get-Date).AddDays(2)
        } | Sort-Object { [datetime]$_.departureDate }
        if ($future.Count -gt 0) {
            $script:Fixtures['futureDep'] = [string]$future[0].id
            if ($future.Count -gt 1) { $script:Fixtures['secondDep'] = [string]$future[1].id }
        }
    }
    if (-not $script:Fixtures['futureDep']) { $script:Fixtures['futureDep'] = '611' }
    if (-not $script:Fixtures['secondDep']) { $script:Fixtures['secondDep'] = '626' }

    # user1's own booking (prefer seed 22)
    $mine = Get-JsonResult -Path '/booking/book/all?page=0&size=20&mine=true' -Token $script:State['user1Token']
    if ($null -ne $mine -and @($mine).Count -gt 0) {
        $preferred = @($mine) | Where-Object { [string]$_.id -eq '22' } | Select-Object -First 1
        if ($null -eq $preferred) { $preferred = @($mine)[0] }
        $script:Fixtures['ownBooking'] = [string]$preferred.id
    }
    # user5's booking (used for ownership tests)
    $mine5 = Get-JsonResult -Path '/booking/book/all?page=0&size=20&mine=true' -Token $script:State['user5Token']
    if ($null -ne $mine5 -and @($mine5).Count -gt 0) {
        $preferred5 = @($mine5) | Where-Object { [string]$_.id -eq '27' } | Select-Object -First 1
        if ($null -eq $preferred5) { $preferred5 = @($mine5)[0] }
        $script:Fixtures['otherBooking'] = [string]$preferred5.id
    }
    # Any existing change request for user1
    $changes = Get-JsonResult -Path '/booking/changes/mine?page=0&size=20' -Token $script:State['user1Token']
    if ($null -ne $changes -and @($changes).Count -gt 0) {
        $script:Fixtures['changeId'] = [string]@($changes)[0].id
    }
    if ($script:State['changeId']) { $script:Fixtures['changeId'] = $script:State['changeId'] }
    if ($script:State['txnRef']) { $script:Fixtures['txnRef'] = $script:State['txnRef'] }
}

function Get-Token([string]$name) { return [string]$script:State[$name] }

function Get-LockedAccountFixture {
    # Best effort: create + lock a throwaway account so the login error path is testable.
    if ($script:State['errLockedEmail'] -and $script:State['errLockedPassword']) { return $true }
    $stamp = (Get-Date).ToString('HHmmssfff')
    $email = "sgu.err.locked.$stamp@sgu-test.local"
    $user = "sguerrlocked$stamp"
    $pass = 'SguErr12345'
    $r = Invoke-Silent -Method POST -Path '/auth/users/register' -Body @{
        username = $user; email = $email; phone = '0901234567'; address = '1 Nguyen Hue, TP.HCM'; password = $pass
    }
    if ($r.Status -ne 200) { return $false }
    $id = [string]$r.Json.result.id
    $lock = Invoke-Silent -Method PUT -Path "/auth/users/$id/status" -Token $script:State['adminToken'] -Body @{
        status = 'LOCKED'; reason = 'Negative test fixture: locked account'
    }
    if ($lock.Status -ne 200) { return $false }
    $script:State['errLockedId'] = $id
    $script:State['errLockedEmail'] = $email
    $script:State['errLockedPassword'] = $pass
    Save-State
    return $true
}

function Get-GatewayLimit {
    # The gateway is configured by env/.local; default values from application.yml.
    return @{ login = 20; register = 5; refresh = 30; password = 10 }
}

function New-VnPaySignature {
    param([hashtable]$Fields, [string]$Secret)
    $pairs = @()
    foreach ($k in ($Fields.Keys | Sort-Object)) {
        $v = [string]$Fields[$k]
        if ([string]::IsNullOrEmpty($v)) { continue }
        $pairs += ([uri]::EscapeDataString($k).Replace('%20', '+') + '=' + [uri]::EscapeDataString($v).Replace('%20', '+'))
    }
    $canonical = $pairs -join '&'
    $hmac = [System.Security.Cryptography.HMACSHA512]::new([System.Text.Encoding]::UTF8.GetBytes($Secret))
    $hash = ($hmac.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($canonical)) | ForEach-Object { $_.ToString('x2') }) -join ''
    return $canonical + '&vnp_SecureHash=' + $hash
}

function Get-VnPaySecret {
    $candidates = @(
        (Join-Path (Split-Path -Parent $here) '.local/payment_service.properties'),
        (Join-Path $here '../.local/payment_service.properties')
    )
    foreach ($p in $candidates) {
        if (Test-Path $p) {
            $line = Get-Content $p | Where-Object { $_ -match '^\s*VNPAY_HASH_SECRET\s*=' } | Select-Object -First 1
            if ($line) { return ($line -replace '^\s*VNPAY_HASH_SECRET\s*=\s*', '').Trim() }
        }
    }
    return 'TESTONLYSECRET0123456789'
}

# ---------------- group helpers ----------------
$script:SeverityFilter = $Severity

function Run-CaseSet {
    param([string]$Group, [object[]]$Cases)
    foreach ($case in $Cases) {
        $params = @{}
        foreach ($key in $case.Keys) { $params[$key] = $case[$key] }
        $params['Group'] = $Group
        Invoke-Case @params | Out-Null
    }
}

function New-ThrowawayAccount {
    $stamp = (Get-Date).ToString('HHmmssfff') + (Get-Random -Minimum 100 -Maximum 999)
    $email = "sgu.err.$stamp@sgu-test.local"
    $user = "sguerr$stamp"
    $phone = '09' + (Get-Random -Minimum 10000000 -Maximum 99999999)
    $pass = 'SguErr12345'
    $r = Invoke-Http -Method POST -Path '/auth/users/register' -Body @{
        username = $user; email = $email; phone = $phone; address = '1 Nguyen Hue, TP.HCM'; password = $pass
    }
    if ($r.Status -ne 200 -or $null -eq $r.Json.result) {
        Write-Host ("[warn] cannot create throwaway account: {0} {1}" -f $r.Status, $r.Text)
        return $null
    }
    $login = Login-Silent $email $pass
    [pscustomobject]@{
        Id = [string]$r.Json.result.id
        Email = $email
        Username = $user
        Password = $pass
        Token = $(if ($login) { $login.token } else { '' })
        Refresh = $(if ($login) { $login.refreshToken } else { '' })
    }
}

function Add-ManualResult {
    param(
        [string]$Group, [string]$Id, [string]$Name, [string]$Severity = 'P2',
        [string]$Method, [string]$Path, [string]$State,
        [string]$ExpectedStatus, [string]$ExpectedCode, $Status, $ActualCode,
        [string]$Response, [string]$Note
    )
    $rec = [pscustomobject]@{
        Group = $Group; Id = $Id; Name = $Name; Severity = $Severity; Method = $Method; Path = $Path
        Status = $Status; ExpectedStatus = $ExpectedStatus; ExpectedCode = $ExpectedCode
        ActualCode = $ActualCode; State = $State; ElapsedMs = 0; Request = ''; Response = $Response
        Note = $Note; Time = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
    }
    Add-Result $rec
    $color = if ($State -eq 'PASS') { 'Green' } elseif ($State -eq 'FAIL') { 'Red' } else { 'Yellow' }
    Write-Host ("[{0}] {1,-10} {2,-48} {3}" -f $State, $Id, $Name, $Note) -ForegroundColor $color
    return $rec
}

function Test-RateLimitCase {
    param([string]$Group, [string]$Id, [string]$Name, [string]$Path, $Body, [int]$Attempts = 40)
    $rateBase = [string]$script:State['rateGateway']
    if ([string]::IsNullOrWhiteSpace($rateBase)) {
        return Add-ManualResult -Group $Group -Id $Id -Name $Name -Method 'POST' -Path $Path -State 'SKIP' `
            -ExpectedStatus '429' -ExpectedCode '1429' -Status '' -ActualCode '' `
            -Response 'rate-limit gateway is not running (start .runtime/start-rate-gateway.ps1 first)' `
            -Note 'opt-in infra'
    }
    $hit = $null
    $statuses = @()
    for ($i = 1; $i -le $Attempts; $i++) {
        $r = Invoke-Http -Method 'POST' -Path $Path -Body $Body -Base $rateBase
        $statuses += $r.Status
        if ($r.Status -eq 429) { $hit = $r; break }
    }
    if ($null -eq $hit) {
        $reachable = @($statuses | Where-Object { $_ -gt 0 }).Count
        if ($reachable -eq 0) {
            return Add-ManualResult -Group $Group -Id $Id -Name $Name -Method 'POST' -Path $Path -State 'SKIP' `
                -ExpectedStatus '429' -ExpectedCode '1429' -Status '' -ActualCode '' `
                -Response "throttled gateway unreachable at $rateBase" -Note 'opt-in infra'
        }
        return Add-ManualResult -Group $Group -Id $Id -Name $Name -Method 'POST' -Path $Path -State 'FAIL' `
            -ExpectedStatus '429' -ExpectedCode '1429' -Status ($statuses -join ',') -ActualCode '' `
            -Response ("statuses: " + ($statuses -join ',')) -Note "no 429 within $Attempts attempts"
    }
    $code = $null
    if ($null -ne $hit.Json -and $null -ne $hit.Json.code) { $code = [string]$hit.Json.code }
    $retry = ''
    if ($null -ne $hit.Headers -and $hit.Headers.ContainsKey('Retry-After')) { $retry = $hit.Headers['Retry-After'] }
    $ok = ($code -eq '1429') -and (-not [string]::IsNullOrWhiteSpace($retry))
    $state = if ($ok) { 'PASS' } else { 'FAIL' }
    return Add-ManualResult -Group $Group -Id $Id -Name $Name -Method 'POST' -Path $Path -State $state `
        -ExpectedStatus '429' -ExpectedCode '1429' -Status $hit.Status -ActualCode $code `
        -Response $hit.Text -Note ("attempts=$($statuses.Count) retry-after=$retry")
}

# ---------------- Group 1: gateway + auth ----------------
function Run-GwAuth {
    $g = 'gw-auth'
    $admin = Get-Token 'adminToken'
    $u1 = Get-Token 'user1Token'
    $u5 = Get-Token 'user5Token'
    $refresh = Get-Token 'adminRefresh'

    # Fixtures: one locked account and one disposable account for logout/password tests.
    $lockedOk = $false
    try { $lockedOk = Get-LockedAccountFixture } catch { $lockedOk = $false }
    $throw = New-ThrowawayAccount

    $cases = @(
        @{ Id='GW-001'; Name='Gateway rejects missing Authorization'; Severity='P0'; Method='GET'; Path='/booking/book/all'; ExpectStatus=401; ExpectCode=1401 }
        @{ Id='GW-002'; Name='Gateway rejects empty bearer value'; Severity='P0'; Method='GET'; Path='/booking/book/all'; Headers=@{ Authorization='Bearer ' }; ExpectStatus=401; ExpectCode=1401 }
        @{ Id='GW-003'; Name='Gateway rejects garbage token'; Severity='P0'; Method='GET'; Path='/booking/book/all'; Token='abc.def.ghi'; ExpectStatus=401; ExpectCode=1401 }
        @{ Id='GW-004'; Name='Gateway rejects refresh token as access token'; Severity='P0'; Method='GET'; Path='/booking/book/all'; Token=$refresh; ExpectStatus=401; ExpectCode=1401 }
        @{ Id='GW-005'; Name='Gateway rejects two Authorization headers'; Severity='P0'; Method='GET'; Path='/booking/book/all'; Headers=@{ Authorization=@("Bearer $admin", "Bearer $u1") }; ExpectStatus=401; ExpectCode=1401 }
        @{ Id='GW-006'; Name='Gateway rejects Basic scheme'; Severity='P1'; Method='GET'; Path='/booking/book/all'; Headers=@{ Authorization='Basic dXNlcjpwYXNz' }; ExpectStatus=401; ExpectCode=1401 }
        @{ Id='GW-007'; Name='Gateway blocks introspect for authenticated caller'; Severity='P0'; Method='POST'; Path='/auth/users/introspect'; Token=$admin; Body=@{ token=$u1 }; ExpectStatus=403; ExpectCode=1403 }
        @{ Id='GW-008'; Name='Internal mail GET is refused before dispatch'; Severity='P2'; Method='GET'; Path='/booking/internal/mail/reminders/dispatch'; ExpectStatus=405,503; ExpectCode=1405,1503 }
        @{ Id='GW-009'; Name='Internal mail without key is unavailable when unconfigured'; Severity='P1'; Method='POST'; Path='/booking/internal/mail/reminders/dispatch'; Body=@{ dryRun=$true }; ExpectStatus=401,503; ExpectCode=1401,1503 }
        @{ Id='GW-010'; Name='Unknown route returns envelope 404'; Severity='P2'; Method='GET'; Path='/does/not/exist'; Token=$admin; ExpectStatus=404; ExpectCode=1404 }
        @{ Id='GW-011'; Name='Wrong method on login is refused before the handler'; Severity='P2'; Method='PUT'; Path='/auth/users/login'; Body=@{ email='x@y.z'; password='x' }; ExpectStatus=401,405; ExpectCode=1401,1405 }
        @{ Id='GW-012'; Name='Login with text/plain is 415'; Severity='P2'; Method='POST'; Path='/auth/users/login'; RawBody='email=a&password=b'; ContentType='text/plain'; ExpectStatus=415; ExpectCode=1415 }
        @{ Id='GW-013'; Name='Malformed JSON body is 400'; Severity='P1'; Method='POST'; Path='/auth/users/login'; RawBody='{"email":'; ExpectStatus=400; ExpectCode=1400 }
        @{ Id='GW-014'; Name='CORS preflight from unknown origin is rejected'; Severity='P1'; Method='OPTIONS'; Path='/auth/users/login'; Headers=@{ Origin='https://evil.example'; 'Access-Control-Request-Method'='POST'; 'Access-Control-Request-Headers'='authorization,content-type' }; ExpectStatus=403 }
        @{ Id='GW-015'; Name='CORS preflight from allowed origin is accepted'; Severity='P2'; Method='OPTIONS'; Path='/auth/users/login'; Headers=@{ Origin='http://localhost:5173'; 'Access-Control-Request-Method'='POST'; 'Access-Control-Request-Headers'='authorization,content-type' }; ExpectStatus=200; Validate={ param($rec,$resp) if (-not $resp.Headers.ContainsKey('Access-Control-Allow-Origin')) { return 'missing Access-Control-Allow-Origin' }; return $true } }

        @{ Id='AU-001'; Name='Login without email is 400'; Severity='P1'; Method='POST'; Path='/auth/users/login'; Body=@{ password='user12345' }; ExpectStatus=400; ExpectCode=1027 }
        @{ Id='AU-002'; Name='Login without password is 400'; Severity='P1'; Method='POST'; Path='/auth/users/login'; Body=@{ email='user1@gmail.com' }; ExpectStatus=400; ExpectCode=1001 }
        @{ Id='AU-003'; Name='Login with invalid email format is 400'; Severity='P1'; Method='POST'; Path='/auth/users/login'; Body=@{ email='not-an-email'; password='user12345' }; ExpectStatus=400; ExpectCode=1001,1009 }
        @{ Id='AU-004'; Name='Login with empty JSON object is 400'; Severity='P1'; Method='POST'; Path='/auth/users/login'; Body=@{}; ExpectStatus=400; ExpectCode=1001,1027 }
        @{ Id='AU-005'; Name='Login with wrong password is 401'; Severity='P0'; Method='POST'; Path='/auth/users/login'; Body=@{ email='user1@gmail.com'; password='wrong-password' }; ExpectStatus=401; ExpectCode=1025 }
        @{ Id='AU-006'; Name='Login with unknown email is 401'; Severity='P1'; Method='POST'; Path='/auth/users/login'; Body=@{ email='nobody@sgu-test.local'; password='user12345' }; ExpectStatus=401; ExpectCode=1025 }
        @{ Id='AU-007'; Name='Locked account looks like bad credentials -> 401/1025'; Severity='P0'; Method='POST'; Path='/auth/users/login'; Body=@{ email=$(if ($lockedOk) { $script:State['errLockedEmail'] } else { '' }); password=$(if ($lockedOk) { $script:State['errLockedPassword'] } else { '' }) }; ExpectStatus=401; ExpectCode=1025; Note='anti-enumeration design: ACCOUNT_INACTIVE is not exposed at login'; SkipReason=$(if ($lockedOk) { '' } else { 'cannot create locked fixture' }) }
        @{ Id='AU-008'; Name='Register with duplicate email is 400'; Severity='P1'; Method='POST'; Path='/auth/users/register'; Body=@{ username=('dup' + (Get-Random)); email='user1@gmail.com'; phone='0901234567'; address='1 Nguyen Hue'; password='SguErr12345' }; ExpectStatus=400; ExpectCode=1021 }
        @{ Id='AU-009'; Name='Register with duplicate username is 400'; Severity='P1'; Method='POST'; Path='/auth/users/register'; Body=@{ username='user1'; email=('new' + (Get-Random) + '@sgu-test.local'); phone='0901234568'; address='1 Nguyen Hue'; password='SguErr12345' }; ExpectStatus=400; ExpectCode=1021 }
        @{ Id='AU-010'; Name='Register with 2-char username is 400'; Severity='P1'; Method='POST'; Path='/auth/users/register'; Body=@{ username='ab'; email=('u' + (Get-Random) + '@sgu-test.local'); phone='0901234569'; address='1 Nguyen Hue'; password='SguErr12345' }; ExpectStatus=400; ExpectCode=1001 }
        @{ Id='AU-011'; Name='Register with invalid email is 400'; Severity='P1'; Method='POST'; Path='/auth/users/register'; Body=@{ username=('badmail' + (Get-Random)); email='bad@'; phone='0901234560'; address='1 Nguyen Hue'; password='SguErr12345' }; ExpectStatus=400; ExpectCode=1009,1001 }
        @{ Id='AU-012'; Name='Register with 8-digit phone is 400'; Severity='P1'; Method='POST'; Path='/auth/users/register'; Body=@{ username=('phone8' + (Get-Random)); email=('p8' + (Get-Random) + '@sgu-test.local'); phone='09012345'; address='1 Nguyen Hue'; password='SguErr12345' }; ExpectStatus=400; ExpectCode=1001 }
        @{ Id='AU-013'; Name='Register with alphabetic phone is 400'; Severity='P1'; Method='POST'; Path='/auth/users/register'; Body=@{ username=('phonex' + (Get-Random)); email=('px' + (Get-Random) + '@sgu-test.local'); phone='09012345abc'; address='1 Nguyen Hue'; password='SguErr12345' }; ExpectStatus=400; ExpectCode=1001 }
        @{ Id='AU-014'; Name='Register with 7-char password is 400'; Severity='P1'; Method='POST'; Path='/auth/users/register'; Body=@{ username=('pass7' + (Get-Random)); email=('pw7' + (Get-Random) + '@sgu-test.local'); phone='0901234571'; address='1 Nguyen Hue'; password='Sgu1234' }; ExpectStatus=400; ExpectCode=1001 }
        @{ Id='AU-015'; Name='Register without address is 400'; Severity='P1'; Method='POST'; Path='/auth/users/register'; Body=@{ username=('noaddr' + (Get-Random)); email=('na' + (Get-Random) + '@sgu-test.local'); phone='0901234572'; password='SguErr12345' }; ExpectStatus=400; ExpectCode=1001 }
        @{ Id='AU-016'; Name='Refresh with access token is 401'; Severity='P0'; Method='POST'; Path='/auth/users/refresh'; Body=@{ token=$u1 }; ExpectStatus=401; ExpectCode=1010,1011,1006 }
        @{ Id='AU-017'; Name='Refresh with empty token is 401'; Severity='P1'; Method='POST'; Path='/auth/users/refresh'; Body=@{ token='' }; ExpectStatus=401; ExpectCode=1010,1011,1006,1001 }
        @{ Id='AU-018'; Name='Refresh with garbage token is 401'; Severity='P1'; Method='POST'; Path='/auth/users/refresh'; Body=@{ token='garbage.token.value' }; ExpectStatus=401; ExpectCode=1010,1011,1006 }
        @{ Id='AU-019'; Name='Change password without token is 401'; Severity='P0'; Method='POST'; Path='/auth/users/Change-password'; Body=@{ oldPassword='user12345'; newPassword='SguErr12345'; confirmPassword='SguErr12345' }; ExpectStatus=401; ExpectCode=1401 }
        @{ Id='AU-020'; Name='Change password with wrong old password is 400'; Severity='P0'; Method='POST'; Path='/auth/users/Change-password'; Token=$throw.Token; Body=@{ oldPassword='wrong-old-pass'; newPassword='SguErr54321'; confirmPassword='SguErr54321' }; ExpectStatus=400; ExpectCode=1005; SkipReason=$(if ($throw) { '' } else { 'no throwaway account' }) }
        @{ Id='AU-021'; Name='Change password with mismatched confirmation is 400'; Severity='P1'; Method='POST'; Path='/auth/users/Change-password'; Token=$throw.Token; Body=@{ oldPassword=$throw.Password; newPassword='SguErr54321'; confirmPassword='SguErr99999' }; ExpectStatus=400; ExpectCode=1012; SkipReason=$(if ($throw) { '' } else { 'no throwaway account' }) }
        @{ Id='AU-022'; Name='Change password with 7-char new password is 400'; Severity='P1'; Method='POST'; Path='/auth/users/Change-password'; Token=$throw.Token; Body=@{ oldPassword=$throw.Password; newPassword='Sgu1234'; confirmPassword='Sgu1234' }; ExpectStatus=400; ExpectCode=1001; SkipReason=$(if ($throw) { '' } else { 'no throwaway account' }) }
        @{ Id='AU-023'; Name='Introspect direct call with garbage token returns invalid'; Severity='P1'; Method='POST'; Path='http://localhost:8080/auth/users/introspect'; Body=@{ token='garbage.token.value' }; ExpectStatus=200; Validate={ param($rec,$resp) if ($resp.Json.result.valid -ne $false) { return 'expected valid=false' }; return $true } }
        @{ Id='AU-029'; Name='Google sign-in without an Authorization header -> 400'; Severity='P1'; Method='POST'; Path='/auth/users/google'; ExpectStatus=400; ExpectCode=1400 }
        @{ Id='AU-030'; Name='Google sign-in with the wrong method is refused at the edge'; Severity='P2'; Method='GET'; Path='/auth/users/google'; ExpectStatus=401; ExpectCode=1401 }
    )
    Run-CaseSet -Group $g -Cases $cases

    # Stateful auth cases: logout and password change must revoke the old token.
    if ($throw) {
        $pre = Invoke-Case -Group $g -Id 'AU-024' -Name 'Logout with valid session is accepted' -Severity 'P1' `
            -Method POST -Path '/auth/users/logout' -Body @{ token = $throw.Token; refreshToken = $throw.Refresh } -ExpectStatus 200
        Invoke-Case -Group $g -Id 'AU-025' -Name 'Access token after logout is rejected' -Severity 'P0' `
            -Method GET -Path '/auth/users/' -Token $throw.Token -ExpectStatus 401 -ExpectCode 1401,1006 | Out-Null

        $pw = New-ThrowawayAccount
        if ($pw) {
            $chg = Invoke-Http -Method POST -Path '/auth/users/Change-password' -Token $pw.Token -Body @{
                oldPassword = $pw.Password; newPassword = 'SguErr54321'; confirmPassword = 'SguErr54321'
            }
            Invoke-Case -Group $g -Id 'AU-026' -Name 'Access token after password change is rejected' -Severity 'P0' `
                -Method GET -Path '/auth/users/' -Token $pw.Token -ExpectStatus 401 -ExpectCode 1401,1006 | Out-Null
            $loginNew = Login-Silent $pw.Email 'SguErr54321'
            if ($null -eq $loginNew) {
                Add-ManualResult -Group $g -Id 'AU-027' -Name 'Login with the new password succeeds' -Method 'POST' -Path '/auth/users/login' -State 'FAIL' -ExpectedStatus '200' -ExpectedCode '' -Status '' -ActualCode '' -Response 'login failed' -Note 'password change did not take effect' | Out-Null
            }
            else {
                Add-ManualResult -Group $g -Id 'AU-027' -Name 'Login with the new password succeeds' -Method 'POST' -Path '/auth/users/login' -State 'PASS' -ExpectedStatus '200' -ExpectedCode '' -Status 200 -ActualCode '' -Response '' -Note '' | Out-Null
            }
        }

        # A refresh token issued before the account is locked must stop working (ACCOUNT_INACTIVE path).
        $lockFlow = New-ThrowawayAccount
        if ($lockFlow) {
            $lock = Invoke-Http -Method PUT -Path "/auth/users/$($lockFlow.Id)/status" -Token (Get-Token 'adminToken') -Body @{
                status = 'LOCKED'; reason = 'Negative test: lock after login'
            }
            if ($lock.Status -eq 200) {
                $r = Invoke-Http -Method POST -Path '/auth/users/refresh' -Body @{ token = $lockFlow.Refresh }
                $code = if ($null -ne $r.Json) { [string]$r.Json.code } else { '' }
                $ok = ($r.Status -in @(401, 403)) -and ($code -in @('1024', '1006', '1010', '1011'))
                Add-ManualResult -Group $g -Id 'AU-028' -Name 'Refresh token after account lock is rejected' -Severity 'P0' `
                    -Method 'POST' -Path '/auth/users/refresh' -State $(if ($ok) { 'PASS' } else { 'FAIL' }) `
                    -ExpectedStatus '401/403' -ExpectedCode '1024/1006/1010/1011' -Status $r.Status -ActualCode $code `
                    -Response $r.Text -Note 'lock is enforced on the refresh path' | Out-Null
            }
            else {
                Add-ManualResult -Group $g -Id 'AU-028' -Name 'Refresh token after account lock is rejected' -Severity 'P0' `
                    -Method 'PUT' -Path "/auth/users/$($lockFlow.Id)/status" -State 'SKIP' -ExpectedStatus '401/403' -ExpectedCode '1024/1006/1010/1011' `
                    -Status $lock.Status -ActualCode '' -Response $lock.Text -Note 'cannot lock the throwaway account' | Out-Null
            }
        }
    }

    if ($IncludeRateLimit) {
        Test-RateLimitCase -Group $g -Id 'GW-RL-01' -Name 'Login throttled after repeated attempts' -Path '/auth/users/login' -Body @{ email='user1@gmail.com'; password='wrong-password' } -Attempts 60 | Out-Null
        Test-RateLimitCase -Group $g -Id 'GW-RL-02' -Name 'Register throttled after repeated attempts' -Path '/auth/users/register' -Body @{ username='rl'; email='rl@sgu-test.local'; phone='0901234567'; address='x'; password='SguErr12345' } -Attempts 60 | Out-Null
    }
    else {
        Add-ManualResult -Group $g -Id 'GW-RL-01' -Name 'Login throttle (429 + Retry-After)' -Method 'POST' -Path '/auth/users/login' -State 'SKIP' -ExpectedStatus '429' -ExpectedCode '1429' -Status '' -ActualCode '' -Response 'run with -IncludeRateLimit and .runtime/start-rate-gateway.ps1' -Note 'opt-in' | Out-Null
        Add-ManualResult -Group $g -Id 'GW-RL-02' -Name 'Register throttle (429 + Retry-After)' -Method 'POST' -Path '/auth/users/register' -State 'SKIP' -ExpectedStatus '429' -ExpectedCode '1429' -Status '' -ActualCode '' -Response 'run with -IncludeRateLimit and .runtime/start-rate-gateway.ps1' -Note 'opt-in' | Out-Null
    }
}

# ---------------- Group 2: user / role / permission / review ----------------
function Run-UserRole {
    $g = 'user-role'
    $admin = Get-Token 'adminToken'
    $u1 = Get-Token 'user1Token'
    $u5 = Get-Token 'user5Token'
    $uid1 = if ($script:State['user1Id']) { [string]$script:State['user1Id'] } else { '3' }
    $uid5 = if ($script:State['user5Id']) { [string]$script:State['user5Id'] } else { '7' }

    $roles = Get-JsonResult -Path '/auth/roles' -Token $admin
    $customerRoleId = ''
    if ($null -ne $roles) {
        $customer = @($roles) | Where-Object { $_.name -ne 'admin' } | Select-Object -First 1
        if ($customer) { $customerRoleId = [string]$customer.id }
    }
    $roleId = [string]$script:State['roleId']

    $cases = @(
        @{ Id='UR-001'; Name='Admin reads a missing user -> 404'; Severity='P1'; Method='GET'; Path='/auth/users/999999'; Token=$admin; ExpectStatus=404; ExpectCode=1028 }
        @{ Id='UR-002'; Name='User id with wrong type -> 400'; Severity='P1'; Method='GET'; Path='/auth/users/abc'; Token=$admin; ExpectStatus=400; ExpectCode=1400 }
        @{ Id='UR-003'; Name='Customer reads another customer profile -> 403'; Severity='P0'; Method='GET'; Path="/auth/users/$uid5"; Token=$u1; ExpectStatus=403; ExpectCode=1007 }
        @{ Id='UR-004'; Name='Customer lists all users -> 403'; Severity='P0'; Method='GET'; Path='/auth/users/list'; Token=$u1; ExpectStatus=403; ExpectCode=1007 }
        @{ Id='UR-005'; Name='Customer resolves display names -> 403'; Severity='P0'; Method='GET'; Path="/auth/users/display-names?ids=$uid1"; Token=$u1; ExpectStatus=403; ExpectCode=1007 }
        @{ Id='UR-006'; Name='Display names with 101 ids -> 400'; Severity='P1'; Method='GET'; Path=('/auth/users/display-names?ids=' + ((1..101) -join ',')); Token=$admin; ExpectStatus=400; ExpectCode=1400 }
        @{ Id='UR-007'; Name='Display names with non-numeric id -> 400'; Severity='P1'; Method='GET'; Path='/auth/users/display-names?ids=abc'; Token=$admin; ExpectStatus=400; ExpectCode=1400 }
        @{ Id='UR-008'; Name='Display names without ids -> 400'; Severity='P1'; Method='GET'; Path='/auth/users/display-names'; Token=$admin; ExpectStatus=400; ExpectCode=1400 }
        @{ Id='UR-009'; Name='Customer creates a managed user -> 403'; Severity='P0'; Method='POST'; Path='/auth/users'; Token=$u1; Body=@{ username=('esc' + (Get-Random)); email=('esc' + (Get-Random) + '@sgu-test.local'); phone='0901234573'; address='x'; password='SguErr12345'; roleId=1 }; ExpectStatus=403; ExpectCode=1007 }
        @{ Id='UR-010'; Name='Admin creates user with duplicate email -> 400'; Severity='P1'; Method='POST'; Path='/auth/users'; Token=$admin; Body=@{ username=('dup' + (Get-Random)); email='user1@gmail.com'; phone='0901234574'; address='x'; password='SguErr12345'; roleId=$customerRoleId }; ExpectStatus=400; ExpectCode=1021 }
        @{ Id='UR-011'; Name='Admin creates user with unknown role -> 404'; Severity='P1'; Method='POST'; Path='/auth/users'; Token=$admin; Body=@{ username=('nr' + (Get-Random)); email=('nr' + (Get-Random) + '@sgu-test.local'); phone='0901234575'; address='x'; password='SguErr12345'; roleId=999999 }; ExpectStatus=404; ExpectCode=1013 }
        @{ Id='UR-012'; Name='Admin creates user without username -> 400'; Severity='P1'; Method='POST'; Path='/auth/users'; Token=$admin; Body=@{ email=('nu' + (Get-Random) + '@sgu-test.local'); phone='0901234576'; address='x'; password='SguErr12345'; roleId=$customerRoleId }; ExpectStatus=400; ExpectCode=1001 }
        @{ Id='UR-013'; Name='Update missing user -> 404'; Severity='P1'; Method='POST'; Path='/auth/users/UpdateUser/999999'; Token=$admin; Form=@{ username='ghost'; phone='0901234577'; address='x' }; ExpectStatus=404; ExpectCode=1026 }
        @{ Id='UR-014'; Name='Update user with non-numeric id -> 400'; Severity='P1'; Method='POST'; Path='/auth/users/UpdateUser/abc'; Token=$admin; Form=@{ username='ghost'; phone='0901234577'; address='x' }; ExpectStatus=400; ExpectCode=1400 }
        @{ Id='UR-015'; Name='Customer updates another customer -> 403'; Severity='P0'; Method='POST'; Path="/auth/users/UpdateUser/$uid5"; Token=$u1; Form=@{ username='hacked'; phone='0901234578'; address='x' }; ExpectStatus=403; ExpectCode=1007 }
        @{ Id='UR-016'; Name='Customer changes account status -> 403'; Severity='P0'; Method='PUT'; Path="/auth/users/$uid5/status"; Token=$u1; Body=@{ status='LOCKED'; reason='negative test' }; ExpectStatus=403; ExpectCode=1007 }
        @{ Id='UR-017'; Name='Invalid account status value -> 400'; Severity='P1'; Method='PUT'; Path="/auth/users/$uid5/status"; Token=$admin; Body=@{ status='SLEEPING'; reason='negative test' }; ExpectStatus=400; ExpectCode=1400 }
        @{ Id='UR-018'; Name='Account status reason over 1000 chars -> 400'; Severity='P1'; Method='PUT'; Path="/auth/users/$uid5/status"; Token=$admin; Body=@{ status='LOCKED'; reason=('x' * 1001) }; ExpectStatus=400; ExpectCode=1001,1400 }
        @{ Id='UR-019'; Name='Admin locks own account -> 403'; Severity='P0'; Method='PUT'; Path="/auth/users/$($script:State['adminId'])/status"; Token=$admin; Body=@{ status='LOCKED'; reason='negative self-lock test' }; ExpectStatus=403; ExpectCode=1403 }
        @{ Id='UR-020'; Name='Archive account that is not locked -> 409'; Severity='P1'; Method='POST'; Path="/auth/users/$uid5/archive"; Token=$admin; Body=@{ reason='negative archive test' }; ExpectStatus=409; ExpectCode=1409 }
        @{ Id='UR-021'; Name='Delete missing user -> 404'; Severity='P1'; Method='DELETE'; Path='/auth/users/999999?reason=negative%20test'; Token=$admin; ExpectStatus=404; ExpectCode=1404,1026 }
        @{ Id='UR-022'; Name='Customer deletes another account -> 403'; Severity='P0'; Method='DELETE'; Path="/auth/users/$uid5"; Token=$u1; ExpectStatus=403; ExpectCode=1007 }
        @{ Id='UR-023'; Name='Customer reads account events -> 403'; Severity='P0'; Method='GET'; Path="/auth/users/$uid5/events"; Token=$u1; ExpectStatus=403; ExpectCode=1007 }
        @{ Id='UR-024'; Name='Events of missing account are an empty list'; Severity='P2'; Method='GET'; Path='/auth/users/999999/events'; Token=$admin; ExpectStatus=200; Note='observation: no existence check, unknown id looks like a user without events' }

        @{ Id='RL-001'; Name='Create role with explicit id -> 400'; Severity='P1'; Method='POST'; Path='/auth/roles'; Token=$admin; Body=@{ id=999; name=('role' + (Get-Random)); description='x' }; ExpectStatus=400; ExpectCode=1400 }
        @{ Id='RL-002'; Name='Create role with a reserved system name -> 400'; Severity='P1'; Method='POST'; Path='/auth/roles'; Token=$admin; Body=@{ name='admin'; description='x' }; ExpectStatus=400; ExpectCode=1017,1015,1029 }
        @{ Id='RL-003'; Name='Create role without name -> 400'; Severity='P1'; Method='POST'; Path='/auth/roles'; Token=$admin; Body=@{ description='x' }; ExpectStatus=400; ExpectCode=1001 }
        @{ Id='RL-004'; Name='Update role without id -> 400'; Severity='P1'; Method='PUT'; Path='/auth/roles'; Token=$admin; Body=@{ name=('upd' + (Get-Random)); description='x' }; ExpectStatus=400; ExpectCode=1400 }
        @{ Id='RL-005'; Name='Read missing role -> 404'; Severity='P1'; Method='GET'; Path='/auth/roles/999999'; Token=$admin; ExpectStatus=404; ExpectCode=1013 }
        @{ Id='RL-006'; Name='Delete missing role -> 404'; Severity='P1'; Method='DELETE'; Path='/auth/roles/999999'; Token=$admin; ExpectStatus=404; ExpectCode=1013 }
        @{ Id='RL-007'; Name='Customer lists roles -> 403'; Severity='P0'; Method='GET'; Path='/auth/roles'; Token=$u1; ExpectStatus=403; ExpectCode=1007 }
        @{ Id='RL-008'; Name='Customer creates role -> 403'; Severity='P0'; Method='POST'; Path='/auth/roles'; Token=$u1; Body=@{ name=('evil' + (Get-Random)); description='x' }; ExpectStatus=403; ExpectCode=1007 }

        @{ Id='PM-001'; Name='Create permission with explicit id -> 400'; Severity='P1'; Method='POST'; Path='/auth/permissions'; Token=$admin; Body=@{ id=999; code=('PM' + (Get-Random)); name='x'; apiPath='/x'; method='GET'; module='test' }; ExpectStatus=400; ExpectCode=1400,1001 }
        @{ Id='PM-002'; Name='Create permission without code -> 400'; Severity='P1'; Method='POST'; Path='/auth/permissions'; Token=$admin; Body=@{ name='x'; apiPath='/x'; method='GET' }; ExpectStatus=400; ExpectCode=1400,1001 }
        @{ Id='PM-003'; Name='Create permission with invalid method -> 400'; Severity='P1'; Method='POST'; Path='/auth/permissions'; Token=$admin; Body=@{ code=('PMX' + (Get-Random)); name='x'; apiPath='/x'; method='TRACE'; module='test' }; ExpectStatus=400; ExpectCode=1001,1400 }
        @{ Id='PM-004'; Name='Read missing permission -> 404'; Severity='P1'; Method='GET'; Path='/auth/permissions/999999'; Token=$admin; ExpectStatus=404; ExpectCode=1014 }
        @{ Id='PM-005'; Name='Update permission without id -> 400'; Severity='P1'; Method='PUT'; Path='/auth/permissions'; Token=$admin; Body=@{ code=('PMU' + (Get-Random)); name='x'; apiPath='/x'; method='GET'; module='test' }; ExpectStatus=400; ExpectCode=1400,1001 }
        @{ Id='PM-006'; Name='Customer lists permissions -> 403'; Severity='P0'; Method='GET'; Path='/auth/permissions/list'; Token=$u1; ExpectStatus=403; ExpectCode=1007 }
        @{ Id='PM-007'; Name='Customer deletes permission -> 403'; Severity='P0'; Method='DELETE'; Path='/auth/permissions/1'; Token=$u1; ExpectStatus=403; ExpectCode=1007 }

        @{ Id='RV-001'; Name='Eligibility without tourId -> 400'; Severity='P1'; Method='GET'; Path='/auth/reviews/eligibility'; Token=$u1; ExpectStatus=400; ExpectCode=1400 }
        @{ Id='RV-002'; Name='Eligibility with non-numeric tourId -> 400'; Severity='P1'; Method='GET'; Path='/auth/reviews/eligibility?tourId=abc'; Token=$u1; ExpectStatus=400; ExpectCode=1400 }
        @{ Id='RV-003'; Name='Create review without token -> 401'; Severity='P0'; Method='POST'; Path='/auth/reviews'; Body=@{ tourId=1; content='x'; rating=5 }; ExpectStatus=401; ExpectCode=1401 }
        @{ Id='RV-004'; Name='Review without a paid, finished booking -> 403'; Severity='P1'; Method='POST'; Path='/auth/reviews'; Token=$u1; Body=@{ tourId=999999; content='Rat tot'; rating=5 }; ExpectStatus=403; ExpectCode=1403; Note='eligibility is checked before the tour lookup' }
        @{ Id='RV-005'; Name='Create review with rating 6 -> 400'; Severity='P1'; Method='POST'; Path='/auth/reviews'; Token=$u1; Body=@{ tourId=1; content='Rat tot'; rating=6 }; ExpectStatus=400; ExpectCode=1001 }
        @{ Id='RV-006'; Name='Create review with rating 0 -> 400'; Severity='P1'; Method='POST'; Path='/auth/reviews'; Token=$u1; Body=@{ tourId=1; content='Rat tot'; rating=0 }; ExpectStatus=400; ExpectCode=1001 }
        @{ Id='RV-007'; Name='Create review with empty content -> 400'; Severity='P1'; Method='POST'; Path='/auth/reviews'; Token=$u1; Body=@{ tourId=1; content=''; rating=5 }; ExpectStatus=400; ExpectCode=1001 }
        @{ Id='RV-008'; Name='Create review with 1001-char content -> 400'; Severity='P1'; Method='POST'; Path='/auth/reviews'; Token=$u1; Body=@{ tourId=1; content=('x' * 1001); rating=5 }; ExpectStatus=400; ExpectCode=1001 }
        @{ Id='RV-009'; Name='High rating with non-numeric size -> 400'; Severity='P2'; Method='GET'; Path='/auth/reviews/high-rating?size=abc'; ExpectStatus=400; ExpectCode=1400 }
        @{ Id='RV-010'; Name='Customer lists all reviews -> 403'; Severity='P0'; Method='GET'; Path='/auth/reviews/list'; Token=$u1; ExpectStatus=403; ExpectCode=1007 }
        @{ Id='RV-011'; Name='Non-numeric tour id on the public review route -> 401'; Severity='P2'; Method='GET'; Path='/auth/reviews/tour/abc'; ExpectStatus=401; ExpectCode=1401 }
        @{ Id='RV-012'; Name='Reviews of a tour without reviews are an empty list'; Severity='P2'; Method='GET'; Path='/auth/reviews/tour/999999'; Token=$u1; ExpectStatus=200; Validate={ param($rec,$resp) if (@($resp.Json.result).Count -ne 0) { return 'expected an empty review list' }; return $true } }
    )
    Run-CaseSet -Group $g -Cases $cases
}

# ---------------- Group 3: tour / location / upload ----------------
function Run-TourLocation {
    $g = 'tour-location'
    $admin = Get-Token 'adminToken'
    $u1 = Get-Token 'user1Token'
    $tourId = if ($script:State['tourId']) { [string]$script:State['tourId'] } else { '1' }

    $pngBytes = New-PngBytes
    $svgBytes = [System.Text.Encoding]::UTF8.GetBytes('<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>')

    $cases = @(
        @{ Id='TL-001'; Name='Tour id 0 -> 404'; Severity='P1'; Method='GET'; Path='/tour/tours/0'; ExpectStatus=404; ExpectCode=1013,1404 }
        @{ Id='TL-002'; Name='Missing tour -> 404'; Severity='P1'; Method='GET'; Path='/tour/tours/999999'; ExpectStatus=404; ExpectCode=1013,1404 }
        @{ Id='TL-003'; Name='Non-numeric tour id is not a public path -> 401'; Severity='P1'; Method='GET'; Path='/tour/tours/abc'; ExpectStatus=401; ExpectCode=1401 }
        @{ Id='TL-007'; Name='Non-numeric tour id for a signed-in caller -> 400'; Severity='P1'; Method='GET'; Path='/tour/tours/abc'; Token=$admin; ExpectStatus=400; ExpectCode=1400 }
        @{ Id='TL-004'; Name='Tour search page=-1 is clamped to page 0'; Severity='P2'; Method='GET'; Path='/tour/tours/search?page=-1'; ExpectStatus=200; Note='observation: paging is not validated here (Spring Pageable defaults)' }
        @{ Id='TL-005'; Name='Tour search size=0 falls back to the default size'; Severity='P2'; Method='GET'; Path='/tour/tours/search?size=0'; ExpectStatus=200; Note='observation: paging is not validated here (Spring Pageable defaults)' }
        @{ Id='TL-006'; Name='Tour search size=1000 is accepted up to the Pageable cap'; Severity='P2'; Method='GET'; Path='/tour/tours/search?size=1000'; ExpectStatus=200; Note='observation: no explicit upper bound; booking/invoice do reject bad paging' }
        @{ Id='TL-010'; Name='Create tour without token -> 401'; Severity='P0'; Method='POST'; Path='/tour/tours'; Form=@{ tourTitle='x' }; ExpectStatus=401; ExpectCode=1401 }
        @{ Id='TL-011'; Name='Create tour as customer -> 403'; Severity='P0'; Method='POST'; Path='/tour/tours'; Token=$u1; Form=@{ tourTitle='x' }; ExpectStatus=403 }
        @{ Id='TL-012'; Name='Create tour without required fields -> 400'; Severity='P1'; Method='POST'; Path='/tour/tours'; Token=$admin; Form=@{ tourTitle='negative test' }; ExpectStatus=400; ExpectCode=1001,1400 }
        @{ Id='TL-013'; Name='Create tour with duration 0 -> 400'; Severity='P1'; Method='POST'; Path='/tour/tours'; Token=$admin; Form=@{ tourProgram='p'; tourTitle='t'; description='d'; duration='0'; departureLocationId='1'; destinationLocationId='2'; vehicleId='1'; tourPriceId='1' }; ExpectStatus=400; ExpectCode=1001,1400 }
        @{ Id='TL-014'; Name='Update missing tour -> 404'; Severity='P1'; Method='PUT'; Path='/tour/tours/999999'; Token=$admin; Form=@{ tourProgram='p'; tourTitle='t'; description='d'; duration='2'; departureLocationId='1'; destinationLocationId='2'; vehicleId='1'; tourPriceId='1' }; ExpectStatus=404; ExpectCode=1013,1404 }
        @{ Id='TL-015'; Name='Update tour with non-numeric id -> 400'; Severity='P1'; Method='PUT'; Path='/tour/tours/abc'; Token=$admin; Form=@{ tourTitle='t' }; ExpectStatus=400; ExpectCode=1400 }
        @{ Id='TL-016'; Name='Delete missing tour -> 404'; Severity='P1'; Method='DELETE'; Path='/tour/tours/999999'; Token=$admin; ExpectStatus=404; ExpectCode=1013,1404 }
        @{ Id='TL-017'; Name='Delete tour as customer -> 403'; Severity='P0'; Method='DELETE'; Path="/tour/tours/$tourId"; Token=$u1; ExpectStatus=403 }
        @{ Id='TL-018'; Name='Retry images for missing tour -> 404'; Severity='P2'; Method='POST'; Path='/tour/tours/999999/images'; Token=$admin; Files=@{ files=(New-FileSpec 'a.png' 0 'image/png' $pngBytes) }; ExpectStatus=404; ExpectCode=1013,1404 }
        @{ Id='TL-019'; Name='Retry images as JSON -> 415'; Severity='P2'; Method='POST'; Path="/tour/tours/$tourId/images"; Token=$admin; Body=@{ files=@() }; ExpectStatus=415; ExpectCode=1415 }
        @{ Id='TL-020'; Name='Retry images without files part -> 400'; Severity='P2'; Method='POST'; Path="/tour/tours/$tourId/images"; Token=$admin; Form=@{ note='x' }; ExpectStatus=400; ExpectCode=1400 }
        @{ Id='TL-021'; Name='POST on the public tour list is refused at the edge -> 401'; Severity='P2'; Method='POST'; Path='/tour/tours/list'; ExpectStatus=401; ExpectCode=1401 }
        @{ Id='TL-022'; Name='DELETE on the public random-tour route is refused -> 401'; Severity='P2'; Method='DELETE'; Path='/tour/tours/random-3'; ExpectStatus=401; ExpectCode=1401 }
        @{ Id='TL-023'; Name='POST on the public vehicle list is refused -> 401'; Severity='P2'; Method='POST'; Path='/tour/vehicles/list'; ExpectStatus=401; ExpectCode=1401 }
        @{ Id='TL-024'; Name='Read a missing itinerary -> 404'; Severity='P1'; Method='GET'; Path='/tour/itineraries/999999'; Token=$admin; ExpectStatus=404; ExpectCode=1015,1404 }
        @{ Id='TL-025'; Name='Itinerary id with the wrong type -> 400'; Severity='P1'; Method='GET'; Path='/tour/itineraries/abc'; Token=$admin; ExpectStatus=400; ExpectCode=1400 }
        @{ Id='TL-026'; Name='Itineraries of a tour without stops are an empty list'; Severity='P2'; Method='GET'; Path='/tour/itineraries/tour/999999'; ExpectStatus=200; Validate={ param($rec,$resp) if (@($resp.Json.result).Count -ne 0) { return 'expected an empty itinerary list' }; return $true } }
        @{ Id='TL-027'; Name='Anonymous advice search with an unknown field -> 400'; Severity='P1'; Method='POST'; Path='/tour/advice/search'; Body=@{ unknownField='x' }; ExpectStatus=400; ExpectCode=4000; ExpectErrorType='INVALID_REQUEST'; Note='the advice routes are public and parse strictly' }
        @{ Id='TL-028'; Name='Anonymous advice locations catalogue is public'; Severity='P2'; Method='GET'; Path='/tour/advice/locations'; ExpectStatus=200; Validate={ param($rec,$resp) if ($null -eq $resp.Json.result) { return 'missing locations catalogue' }; return $true } }
        @{ Id='TL-029'; Name='Advice search with an unknown field -> 400'; Severity='P1'; Method='POST'; Path='/tour/advice/search'; Token=$admin; Body=@{ unknownField='x' }; ExpectStatus=400; ExpectCode=4000; ExpectErrorType='INVALID_REQUEST' }
        @{ Id='TL-030'; Name='Advice search over 16 kB -> 413 PAYLOAD_TOO_LARGE'; Severity='P1'; Method='POST'; Path='/tour/advice/search'; Token=$admin; RawBody=('{"note":"' + ('x' * 20000) + '"}'); ExpectStatus=413; ExpectCode=4130; ExpectErrorType='PAYLOAD_TOO_LARGE' }
        @{ Id='TL-031'; Name='Departure location search without keyword -> 400'; Severity='P1'; Method='GET'; Path='/tour/locations/departures/search'; ExpectStatus=400; ExpectCode=1400 }
        @{ Id='TL-032'; Name='Destination search with an empty keyword returns nothing'; Severity='P2'; Method='GET'; Path='/tour/locations/destinations/search?keyword='; ExpectStatus=200; Validate={ param($rec,$resp) if (@($resp.Json.result).Count -ne 0) { return 'expected an empty result' }; return $true } }
        @{ Id='TL-033'; Name='POST on the public location list is refused -> 401'; Severity='P2'; Method='POST'; Path='/tour/locations/all'; ExpectStatus=401; ExpectCode=1401 }
        @{ Id='TL-034'; Name='POST on the public departure locations is refused -> 401'; Severity='P2'; Method='POST'; Path='/tour/locations/departures'; ExpectStatus=401; ExpectCode=1401 }
        @{ Id='TL-035'; Name='POST on the public destination locations is refused -> 401'; Severity='P2'; Method='POST'; Path='/tour/locations/destinations'; ExpectStatus=401; ExpectCode=1401 }
        @{ Id='TL-036'; Name='POST on the public attractive destinations is refused -> 401'; Severity='P2'; Method='POST'; Path='/tour/locations/destinations/attractive'; ExpectStatus=401; ExpectCode=1401 }

        @{ Id='LC-001'; Name='Create location as customer -> 403'; Severity='P0'; Method='POST'; Path='/tour/locations'; Token=$u1; Form=@{ city='X'; type='DESTINATION' }; ExpectStatus=403 }
        @{ Id='LC-002'; Name='Create location as JSON -> 415'; Severity='P2'; Method='POST'; Path='/tour/locations'; Token=$admin; Body=@{ city='X'; type='DESTINATION' }; ExpectStatus=415; ExpectCode=1415 }
        @{ Id='LC-003'; Name='Create location without fields -> 400'; Severity='P1'; Method='POST'; Path='/tour/locations'; Token=$admin; Form=@{ note='x' }; ExpectStatus=400; ExpectCode=1001,1400 }
        @{ Id='LC-004'; Name='Create location with invalid type -> 400'; Severity='P1'; Method='POST'; Path='/tour/locations'; Token=$admin; Form=@{ city='X'; type='NOT_A_TYPE' }; ExpectStatus=400; ExpectCode=1400 }
        @{ Id='LC-005'; Name='Update missing location -> 404'; Severity='P1'; Method='PUT'; Path='/tour/locations/999999'; Token=$admin; Form=@{ city='X'; type='DESTINATION' }; ExpectStatus=404; ExpectCode=1016,1404 }
        @{ Id='LC-006'; Name='Update location as customer -> 403'; Severity='P0'; Method='PUT'; Path='/tour/locations/1'; Token=$u1; Form=@{ city='X'; type='DESTINATION' }; ExpectStatus=403 }
        @{ Id='LC-007'; Name='Upload SVG as tour image -> 400'; Severity='P2'; Method='POST'; Path="/tour/tours/$tourId/images"; Token=$admin; Files=@{ files=(New-FileSpec 'evil.svg' 0 'image/svg+xml' $svgBytes) }; ExpectStatus=400; ExpectCode=1400,1413 }
    )
    Run-CaseSet -Group $g -Cases $cases
}

# ---------------- Group 4: pricing / vehicle / itinerary / departure ----------------
function Run-PricingDeparture {
    $g = 'pricing-departure'
    $admin = Get-Token 'adminToken'
    $u1 = Get-Token 'user1Token'
    $futureDep = [string]$script:Fixtures['futureDep']

    $vehicles = Get-JsonResult -Path '/tour/vehicles/list'
    $vehicleId = ''
    if ($null -ne $vehicles -and @($vehicles).Count -gt 0) { $vehicleId = [string]@($vehicles)[0].id }

    $now = Get-Date
    $past = $now.AddDays(-3).ToString('yyyy-MM-ddTHH:mm:ss')
    $soon = $now.AddDays(20).ToString('yyyy-MM-ddTHH:mm:ss')
    $later = $now.AddDays(25).ToString('yyyy-MM-ddTHH:mm:ss')

    $cases = @(
        @{ Id='PD-001'; Name='Create price without token -> 401'; Severity='P0'; Method='POST'; Path='/pricing/prices'; Body=@{ adultPrice=100000 }; ExpectStatus=401; ExpectCode=1401 }
        @{ Id='PD-002'; Name='Create price as customer -> 403'; Severity='P0'; Method='POST'; Path='/pricing/prices'; Token=$u1; Body=@{ adultPrice=100000 }; ExpectStatus=403 }
        @{ Id='PD-003'; Name='Create price with negative amount -> 400'; Severity='P1'; Method='POST'; Path='/pricing/prices'; Token=$admin; Body=@{ adultPrice=-1000 }; ExpectStatus=400; ExpectCode=1400 }
        @{ Id='PD-004'; Name='Update missing price -> 404'; Severity='P1'; Method='PUT'; Path='/pricing/prices/999999'; Token=$admin; Body=@{ adultPrice=100000; childPrice=50000; toddlerPrice=30000; infantPrice=0; singleSupplementPrice=0 }; ExpectStatus=404; ExpectCode=1404,1013 }
        @{ Id='PD-005'; Name='Read missing price -> 404'; Severity='P1'; Method='GET'; Path='/pricing/prices/999999'; ExpectStatus=404; ExpectCode=1013,1404 }
        @{ Id='PD-006'; Name='Customer lists price table -> 403'; Severity='P0'; Method='GET'; Path='/pricing/prices'; Token=$u1; ExpectStatus=403 }
        @{ Id='PD-007'; Name='Non-numeric price id is not public -> 401'; Severity='P1'; Method='GET'; Path='/pricing/prices/abc'; ExpectStatus=401; ExpectCode=1401 }
        @{ Id='PD-008'; Name='Batch price without ids -> 400'; Severity='P1'; Method='POST'; Path='/pricing/prices/batch'; Body=@{}; ExpectStatus=400; ExpectCode=1001 }
        @{ Id='PD-009'; Name='Batch price with zero id -> 400'; Severity='P1'; Method='POST'; Path='/pricing/prices/batch'; Body=@{ ids=@(0) }; ExpectStatus=400; ExpectCode=1001 }
        @{ Id='PD-010'; Name='Batch price with 101 ids -> 400'; Severity='P1'; Method='POST'; Path='/pricing/prices/batch'; Body=@{ ids=@(1..101) }; ExpectStatus=400; ExpectCode=1001 }
        @{ Id='PD-011'; Name='Batch price with empty list -> 400'; Severity='P1'; Method='POST'; Path='/pricing/prices/batch'; Body=@{ ids=@() }; ExpectStatus=400; ExpectCode=1001 }

        @{ Id='PD-020'; Name='Missing departure -> 404'; Severity='P1'; Method='GET'; Path='/tour/tour-departures/999999'; ExpectStatus=404; ExpectCode=1014,1404 }
        @{ Id='PD-021'; Name='Non-numeric departure id is not public -> 401'; Severity='P1'; Method='GET'; Path='/tour/tour-departures/abc'; ExpectStatus=401; ExpectCode=1401 }
        @{ Id='PD-022'; Name='Departure list without token -> 401'; Severity='P1'; Method='GET'; Path='/tour/tour-departures/list'; ExpectStatus=401; ExpectCode=1401 }
        @{ Id='PD-023'; Name='Create departure without token -> 401'; Severity='P0'; Method='POST'; Path='/tour/tour-departures'; Body=@{ departureDate=$soon; returnDate=$later; capacity=20; tourId=1 }; ExpectStatus=401; ExpectCode=1401 }
        @{ Id='PD-024'; Name='Create departure as customer -> 403'; Severity='P0'; Method='POST'; Path='/tour/tour-departures'; Token=$u1; Body=@{ departureDate=$soon; returnDate=$later; capacity=20; tourId=1 }; ExpectStatus=403 }
        @{ Id='PD-025'; Name='Departure with return before departure -> 400'; Severity='P1'; Method='POST'; Path='/tour/tour-departures'; Token=$admin; Body=@{ departureDate=$later; returnDate=$soon; capacity=20; tourId=1 }; ExpectStatus=400; ExpectCode=1400 }
        @{ Id='PD-026'; Name='Departure in the past -> 400'; Severity='P1'; Method='POST'; Path='/tour/tour-departures'; Token=$admin; Body=@{ departureDate=$past; returnDate=$soon; capacity=20; tourId=1 }; ExpectStatus=400; ExpectCode=1400 }
        @{ Id='PD-027'; Name='Departure capacity 0 -> 400'; Severity='P1'; Method='POST'; Path='/tour/tour-departures'; Token=$admin; Body=@{ departureDate=$soon; returnDate=$later; capacity=0; tourId=1 }; ExpectStatus=400; ExpectCode=1400 }
        @{ Id='PD-028'; Name='Departure for missing tour -> 404'; Severity='P1'; Method='POST'; Path='/tour/tour-departures'; Token=$admin; Body=@{ departureDate=$soon; returnDate=$later; capacity=20; tourId=999999 }; ExpectStatus=404; ExpectCode=1013,1404 }
        @{ Id='PD-029'; Name='Update missing departure -> 404'; Severity='P1'; Method='PUT'; Path='/tour/tour-departures/999999'; Token=$admin; Body=@{ departureDate=$soon; returnDate=$later; capacity=20; tourId=1; expectedVersion=0 }; ExpectStatus=404; ExpectCode=1014,1404 }
        @{ Id='PD-030'; Name='Update departure with stale version -> 409'; Severity='P0'; Method='PUT'; Path="/tour/tour-departures/$futureDep"; Token=$admin; Body=@{ departureDate=$soon; returnDate=$later; capacity=20; tourId=1; expectedVersion=999999 }; ExpectStatus=409; ExpectCode=1409; SkipReason=$(if ($futureDep) { '' } else { 'no future departure fixture' }) }
        @{ Id='PD-031'; Name='Departure history as customer -> 403'; Severity='P0'; Method='GET'; Path='/tour/tour-departures/tour/1/all'; Token=$u1; ExpectStatus=403 }
        @{ Id='PD-032'; Name='Departure capacity without token -> 401'; Severity='P1'; Method='GET'; Path="/tour/tour-departures/$futureDep/capacity"; ExpectStatus=401; ExpectCode=1401 }

        @{ Id='PD-040'; Name='Create itinerary without token -> 401'; Severity='P0'; Method='POST'; Path='/tour/itineraries'; Body=@{ title='t'; description='d'; meal='m'; tourId=1 }; ExpectStatus=401; ExpectCode=1401 }
        @{ Id='PD-041'; Name='Create itinerary as customer -> 403'; Severity='P0'; Method='POST'; Path='/tour/itineraries'; Token=$u1; Body=@{ title='t'; description='d'; meal='m'; tourId=1 }; ExpectStatus=403 }
        @{ Id='PD-042'; Name='Create itinerary without title -> 400'; Severity='P1'; Method='POST'; Path='/tour/itineraries'; Token=$admin; Body=@{ description='d'; meal='m'; tourId=1 }; ExpectStatus=400; ExpectCode=1001 }
        @{ Id='PD-043'; Name='Create itinerary with tourId 0 -> 400'; Severity='P1'; Method='POST'; Path='/tour/itineraries'; Token=$admin; Body=@{ title='t'; description='d'; meal='m'; tourId=0 }; ExpectStatus=400; ExpectCode=1001 }
        @{ Id='PD-044'; Name='Create itinerary for missing tour -> 400/404'; Severity='P1'; Method='POST'; Path='/tour/itineraries'; Token=$admin; Body=@{ title='t'; description='d'; meal='m'; tourId=999999 }; ExpectStatus=400,404; ExpectCode=1013,1400,1404 }
        @{ Id='PD-045'; Name='Update missing itinerary -> 404'; Severity='P1'; Method='PUT'; Path='/tour/itineraries/999999'; Token=$admin; Body=@{ title='t'; description='d'; meal='m'; tourId=1 }; ExpectStatus=404; ExpectCode=1015,1404 }
        @{ Id='PD-046'; Name='Delete missing itinerary -> 404'; Severity='P1'; Method='DELETE'; Path='/tour/itineraries/999999'; Token=$admin; ExpectStatus=404; ExpectCode=1015,1404 }
        @{ Id='PD-047'; Name='Itinerary list without token -> 401'; Severity='P1'; Method='GET'; Path='/tour/itineraries/all'; ExpectStatus=401; ExpectCode=1401 }

        @{ Id='PD-050'; Name='Create vehicle without fields -> 400'; Severity='P1'; Method='POST'; Path='/tour/vehicles'; Token=$admin; Body=@{ note='x' }; ExpectStatus=400; ExpectCode=1001 }
        @{ Id='PD-051'; Name='Create vehicle as customer -> 403'; Severity='P0'; Method='POST'; Path='/tour/vehicles'; Token=$u1; Body=@{ id='V1'; vehicleType='Bus' }; ExpectStatus=403 }
        @{ Id='PD-052'; Name='Create vehicle with duplicate id -> 409'; Severity='P1'; Method='POST'; Path='/tour/vehicles'; Token=$admin; Body=@{ id=$vehicleId; vehicleType='Bus' }; ExpectStatus=409; ExpectCode=1409,1001; SkipReason=$(if ($vehicleId) { '' } else { 'no vehicle fixture' }) }
    )
    Run-CaseSet -Group $g -Cases $cases
}

# ---------------- Group 5: booking / customer / advice ----------------
function New-PendingBooking {
    param([switch]$Fresh)
    if (-not $Fresh -and $script:State['errPendingBookingId']) { return [string]$script:State['errPendingBookingId'] }
    $dep = [string]$script:Fixtures['futureDep']
    $uid1 = [string]$script:State['user1Id']
    if ([string]::IsNullOrWhiteSpace($dep) -or [string]::IsNullOrWhiteSpace($uid1)) { return '' }
    $body = @{
        userId = $uid1
        tourDepartureId = $dep
        clientKey = (New-Uuid)
        listOfCustomers = @(
            @{ fullName = 'Negative Fixture Adult'; birthdate = '1990-05-05'; gender = 'MALE'; address = '1 Nguyen Hue, TP.HCM'; status = 'BOOKED' }
        )
    }
    $r = Invoke-Http -Method POST -Path '/booking/book/create' -Token (Get-Token 'user1Token') -Body $body
    if ($r.Status -eq 200 -and $null -ne $r.Json.result) {
        $script:State['errPendingBookingId'] = [string]$r.Json.result.id
        Save-State
        return [string]$r.Json.result.id
    }
    Write-Host ("[warn] cannot create pending booking: {0} {1}" -f $r.Status, $r.Text)
    return ''
}

function Run-Booking {
    $g = 'booking'
    $admin = Get-Token 'adminToken'
    $u1 = Get-Token 'user1Token'
    $u5 = Get-Token 'user5Token'
    $uid1 = [string]$script:State['user1Id']
    $uid5 = [string]$script:State['user5Id']
    $dep = [string]$script:Fixtures['futureDep']
    $own = [string]$script:Fixtures['ownBooking']
    $other = [string]$script:Fixtures['otherBooking']
    $seats41 = @()
    for ($i = 0; $i -lt 41; $i++) {
        $seats41 += @{ fullName = "Passenger $i"; birthdate = '1990-01-01'; gender = 'MALE'; address = 'HCM'; status = 'BOOKED' }
    }

    $cases = @(
        @{ Id='BK-001'; Name='Create booking without token -> 401'; Severity='P0'; Method='POST'; Path='/booking/book/create'; Body=@{ userId=$uid1; tourDepartureId=$dep; listOfCustomers=@(@{ fullName='A'; birthdate='1990-01-01'; gender='MALE'; address='HCM' }) }; ExpectStatus=401; ExpectCode=1401 }
        @{ Id='BK-002'; Name='Create booking for another account -> 403'; Severity='P0'; Method='POST'; Path='/booking/book/create'; Token=$u1; Body=@{ userId=$uid5; tourDepartureId=$dep; listOfCustomers=@(@{ fullName='A'; birthdate='1990-01-01'; gender='MALE'; address='HCM' }) }; ExpectStatus=403; ExpectCode=1403 }
        @{ Id='BK-003'; Name='Create booking without userId -> 400'; Severity='P1'; Method='POST'; Path='/booking/book/create'; Token=$u1; Body=@{ tourDepartureId=$dep; listOfCustomers=@(@{ fullName='A'; birthdate='1990-01-01'; gender='MALE'; address='HCM' }) }; ExpectStatus=400; ExpectCode=1001 }
        @{ Id='BK-004'; Name='Create booking without departure -> 400'; Severity='P1'; Method='POST'; Path='/booking/book/create'; Token=$u1; Body=@{ userId=$uid1; listOfCustomers=@(@{ fullName='A'; birthdate='1990-01-01'; gender='MALE'; address='HCM' }) }; ExpectStatus=400; ExpectCode=1001 }
        @{ Id='BK-005'; Name='Create booking without passengers -> 400'; Severity='P1'; Method='POST'; Path='/booking/book/create'; Token=$u1; Body=@{ userId=$uid1; tourDepartureId=$dep; listOfCustomers=@() }; ExpectStatus=400; ExpectCode=1001 }
        @{ Id='BK-006'; Name='Create booking for missing departure -> 404'; Severity='P1'; Method='POST'; Path='/booking/book/create'; Token=$u1; Body=@{ userId=$uid1; tourDepartureId='999999'; listOfCustomers=@(@{ fullName='A'; birthdate='1990-01-01'; gender='MALE'; address='HCM' }) }; ExpectStatus=404; ExpectCode=1404 }
        @{ Id='BK-007'; Name='Create booking with non-numeric departure -> 400'; Severity='P1'; Method='POST'; Path='/booking/book/create'; Token=$u1; Body=@{ userId=$uid1; tourDepartureId='abc'; listOfCustomers=@(@{ fullName='A'; birthdate='1990-01-01'; gender='MALE'; address='HCM' }) }; ExpectStatus=400; ExpectCode=1400 }
        @{ Id='BK-008'; Name='Create booking with 41 passengers -> 409'; Severity='P1'; Method='POST'; Path='/booking/book/create'; Token=$u1; Body=@{ userId=$uid1; tourDepartureId=$dep; listOfCustomers=$seats41 }; ExpectStatus=409; ExpectCode=1409 }
        @{ Id='BK-009'; Name='Passenger with future birthdate -> 400'; Severity='P1'; Method='POST'; Path='/booking/book/create'; Token=$u1; Body=@{ userId=$uid1; tourDepartureId=$dep; listOfCustomers=@(@{ fullName='A'; birthdate='2999-01-01'; gender='MALE'; address='HCM' }) }; ExpectStatus=400; ExpectCode=1400 }
        @{ Id='BK-010'; Name='Passenger with invalid gender -> 400'; Severity='P1'; Method='POST'; Path='/booking/book/create'; Token=$u1; Body=@{ userId=$uid1; tourDepartureId=$dep; listOfCustomers=@(@{ fullName='A'; birthdate='1990-01-01'; gender='ALIEN'; address='HCM' }) }; ExpectStatus=400; ExpectCode=1400 }
        @{ Id='BK-011'; Name='Passenger address over 255 chars -> 400'; Severity='P1'; Method='POST'; Path='/booking/book/create'; Token=$u1; Body=@{ userId=$uid1; tourDepartureId=$dep; listOfCustomers=@(@{ fullName='A'; birthdate='1990-01-01'; gender='MALE'; address=('x' * 256) }) }; ExpectStatus=400; ExpectCode=1400 }
        @{ Id='BK-012'; Name='Invalid clientKey -> 400'; Severity='P1'; Method='POST'; Path='/booking/book/create'; Token=$u1; Body=@{ userId=$uid1; tourDepartureId=$dep; clientKey='not-a-uuid'; listOfCustomers=@(@{ fullName='A'; birthdate='1990-01-01'; gender='MALE'; address='HCM' }) }; ExpectStatus=400; ExpectCode=1400 }

        @{ Id='BK-020'; Name='Read booking id 0 -> 404'; Severity='P1'; Method='GET'; Path='/booking/book/0'; Token=$u1; ExpectStatus=404; ExpectCode=1012 }
        @{ Id='BK-021'; Name='Read booking id -1 -> 404'; Severity='P1'; Method='GET'; Path='/booking/book/-1'; Token=$u1; ExpectStatus=404; ExpectCode=1012 }
        @{ Id='BK-022'; Name='Read booking with non-numeric id -> 400'; Severity='P1'; Method='GET'; Path='/booking/book/abc'; Token=$u1; ExpectStatus=400; ExpectCode=1400 }
        @{ Id='BK-023'; Name='Read missing booking -> 404'; Severity='P1'; Method='GET'; Path='/booking/book/999999'; Token=$u1; ExpectStatus=404; ExpectCode=1404,1012 }
        @{ Id='BK-024'; Name='Read another customer booking -> 403'; Severity='P0'; Method='GET'; Path="/booking/book/$other"; Token=$u1; ExpectStatus=403; ExpectCode=1403; SkipReason=$(if ($other) { '' } else { 'no other booking fixture' }) }
        @{ Id='BK-025'; Name='Booking list page=-1 is clamped'; Severity='P2'; Method='GET'; Path='/booking/book/all?page=-1&size=20&mine=true'; Token=$u1; ExpectStatus=200; Note='observation: PageSlice clamps page/size instead of rejecting them' }
        @{ Id='BK-026'; Name='Booking list size=0 is clamped'; Severity='P2'; Method='GET'; Path='/booking/book/all?page=0&size=0&mine=true'; Token=$u1; ExpectStatus=200; Note='observation: PageSlice clamps page/size instead of rejecting them' }
        @{ Id='BK-027'; Name='Delete missing booking -> 404'; Severity='P1'; Method='DELETE'; Path='/booking/book/999999'; Token=$u1; ExpectStatus=404; ExpectCode=1404,1012 }
        @{ Id='BK-028'; Name='Delete another customer booking -> 403'; Severity='P0'; Method='DELETE'; Path="/booking/book/$other"; Token=$u1; ExpectStatus=403; ExpectCode=1403; SkipReason=$(if ($other) { '' } else { 'no other booking fixture' }) }

        @{ Id='BK-030'; Name='Customer of missing booking -> 404'; Severity='P1'; Method='GET'; Path='/booking/customer/booking/999999'; Token=$u1; ExpectStatus=404; ExpectCode=1404 }
        @{ Id='BK-031'; Name='Missing customer id -> 404'; Severity='P1'; Method='GET'; Path='/booking/customer/999999'; Token=$u1; ExpectStatus=404; ExpectCode=1404 }
        @{ Id='BK-032'; Name='Blocked customer create endpoint -> 409'; Severity='P1'; Method='POST'; Path='/booking/customer/create'; Token=$u1; RawBody='[{"fullName":"A","birthdate":"1990-01-01","gender":"MALE","address":"HCM"}]'; ExpectStatus=409; ExpectCode=1409 }
        @{ Id='BK-033'; Name='Customer list page=-1/size=0 is clamped and scoped'; Severity='P2'; Method='GET'; Path='/booking/customer/all?page=-1&size=0'; Token=$u1; ExpectStatus=200; Note='observation: paging is clamped and the result stays scoped to the caller' }

        @{ Id='BK-040'; Name='Advice mine without token -> 401'; Severity='P0'; Method='GET'; Path='/booking/advice/mine'; ExpectStatus=401; ExpectCode=1401 }
        @{ Id='BK-041'; Name='Advice mine with non-numeric bookingId -> 400'; Severity='P1'; Method='GET'; Path='/booking/advice/mine?bookingId=abc'; Token=$u1; ExpectStatus=400; ExpectCode=1400 }
        @{ Id='BK-042'; Name='Advice mine ignores a booking that is not mine'; Severity='P0'; Method='GET'; Path="/booking/advice/mine?bookingId=$other"; Token=$u1; ExpectStatus=200; Validate={ param($rec,$resp) if (@($resp.Json.result.bookings).Count -ne 0) { return 'foreign booking leaked advice' }; return $true }; SkipReason=$(if ($other) { '' } else { 'no other booking fixture' }) }
        @{ Id='BK-043'; Name='Review eligibility without tourId -> 400'; Severity='P1'; Method='GET'; Path='/booking/advice/review-eligibility'; Token=$u1; ExpectStatus=400; ExpectCode=1400,1001 }
        @{ Id='BK-044'; Name='Review eligibility with non-numeric tourId -> 400'; Severity='P1'; Method='GET'; Path='/booking/advice/review-eligibility?tourId=abc'; Token=$u1; ExpectStatus=400; ExpectCode=1400,1001 }
        @{ Id='BK-045'; Name='Review eligibility with tourId 0 does not 500'; Severity='P2'; Method='GET'; Path='/booking/advice/review-eligibility?tourId=0'; Token=$u1; ExpectStatus=200,400; Validate={ param($rec,$resp) if ($resp.Status -ge 500) { return 'server error on tourId=0' }; return $true } }
    )
    Run-CaseSet -Group $g -Cases $cases

    $pending = New-PendingBooking
    if ($pending) {
        Add-ManualResult -Group $g -Id 'BK-050' -Name 'Fixture: pending-payment booking created' -Method 'POST' -Path '/booking/book/create' -State 'PASS' -ExpectedStatus '200' -ExpectedCode '' -Status 200 -ActualCode '' -Response '' -Note "bookingId=$pending" | Out-Null
    }
    else {
        Add-ManualResult -Group $g -Id 'BK-050' -Name 'Fixture: pending-payment booking created' -Method 'POST' -Path '/booking/book/create' -State 'SKIP' -ExpectedStatus '200' -ExpectedCode '' -Status '' -ActualCode '' -Response 'no seat/price available for a new booking' -Note 'payment group will skip amount checks' | Out-Null
    }
}

# ---------------- Group 6: change request / shop incident ----------------
function Run-Change {
    $g = 'change'
    $admin = Get-Token 'adminToken'
    $u1 = Get-Token 'user1Token'
    $u5 = Get-Token 'user5Token'
    $own = [string]$script:Fixtures['ownBooking']
    $other = [string]$script:Fixtures['otherBooking']
    $dep = [string]$script:Fixtures['futureDep']
    $changeId = [string]$script:Fixtures['changeId']
    $depVersion = 0
    $depInfo = Get-JsonResult -Path "/tour/tour-departures/$dep"
    if ($null -ne $depInfo -and $null -ne $depInfo.version) { $depVersion = [int]$depInfo.version }
    $now = Get-Date
    $future = $now.AddDays(30).ToString('yyyy-MM-ddTHH:mm:ss')
    $later = $now.AddDays(35).ToString('yyyy-MM-ddTHH:mm:ss')
    $past = $now.AddDays(-10).ToString('yyyy-MM-ddTHH:mm:ss')
    $shopBody = @{
        clientKey = (New-Uuid); sourceId = [int]$dep; expectedVersion = 0; reasonCode = 'WEATHER'
        reason = 'Thoi tiet xau khong the khoi hanh an toan cho khach'
        evidence = 'Cong van so 123/2026 cua co quan khi tuong'
        originalCannotDepart = $true; departureAt = $future; returnAt = $later
        capacity = 20; responseDeadline = $future
    }

    $cases = @(
        @{ Id='CH-001'; Name='My changes without token -> 401'; Severity='P0'; Method='GET'; Path='/booking/changes/mine'; ExpectStatus=401; ExpectCode=1401 }
        @{ Id='CH-002'; Name='Eligible changes page=-1 is clamped'; Severity='P2'; Method='GET'; Path='/booking/changes/eligible?page=-1'; Token=$u1; ExpectStatus=200; Note='observation: paging is clamped here, booking/invoice reject bad paging' }
        @{ Id='CH-003'; Name='Options without bookingId -> 400'; Severity='P1'; Method='GET'; Path='/booking/changes/options'; Token=$u1; ExpectStatus=400; ExpectCode=1400 }
        @{ Id='CH-004'; Name='Options with non-numeric bookingId -> 400'; Severity='P1'; Method='GET'; Path='/booking/changes/options?bookingId=abc'; Token=$u1; ExpectStatus=400; ExpectCode=1400 }
        @{ Id='CH-005'; Name='Options for another booking -> 403'; Severity='P0'; Method='GET'; Path="/booking/changes/options?bookingId=$other"; Token=$u1; ExpectStatus=403; ExpectCode=1403; SkipReason=$(if ($other) { '' } else { 'no other booking fixture' }) }
        @{ Id='CH-006'; Name='Options for missing booking -> 404'; Severity='P1'; Method='GET'; Path='/booking/changes/options?bookingId=999999'; Token=$u1; ExpectStatus=404; ExpectCode=1404 }

        @{ Id='CH-010'; Name='Customer change without token -> 401'; Severity='P0'; Method='POST'; Path='/booking/changes/customer'; Body=@{ clientKey=(New-Uuid); bookingId=[int]$own; targetId=[int]$dep; reason='Muon doi lich' }; ExpectStatus=401; ExpectCode=1401 }
        @{ Id='CH-011'; Name='Customer change without clientKey -> 400'; Severity='P1'; Method='POST'; Path='/booking/changes/customer'; Token=$u1; Body=@{ bookingId=[int]$own; targetId=[int]$dep; reason='Muon doi lich' }; ExpectStatus=400; ExpectCode=1001 }
        @{ Id='CH-012'; Name='Customer change with targetId 0 -> 400'; Severity='P1'; Method='POST'; Path='/booking/changes/customer'; Token=$u1; Body=@{ clientKey=(New-Uuid); bookingId=[int]$own; targetId=0; reason='Muon doi lich' }; ExpectStatus=400; ExpectCode=1001 }
        @{ Id='CH-013'; Name='Customer change with empty reason -> 400'; Severity='P1'; Method='POST'; Path='/booking/changes/customer'; Token=$u1; Body=@{ clientKey=(New-Uuid); bookingId=[int]$own; targetId=[int]$dep; reason='' }; ExpectStatus=400; ExpectCode=1001 }
        @{ Id='CH-014'; Name='Customer change for another booking -> 403'; Severity='P0'; Method='POST'; Path='/booking/changes/customer'; Token=$u1; Body=@{ clientKey=(New-Uuid); bookingId=[int]$other; targetId=[int]$dep; reason='Muon doi lich' }; ExpectStatus=403; ExpectCode=1403; SkipReason=$(if ($other) { '' } else { 'no other booking fixture' }) }
        @{ Id='CH-015'; Name='Customer change with unknown booking -> 404'; Severity='P1'; Method='POST'; Path='/booking/changes/customer'; Token=$u1; Body=@{ clientKey=(New-Uuid); bookingId=999999; targetId=[int]$dep; reason='Muon doi lich' }; ExpectStatus=404; ExpectCode=1404 }

        @{ Id='CH-020'; Name='Admin change list as customer -> 403'; Severity='P0'; Method='GET'; Path='/booking/changes/admin'; Token=$u1; ExpectStatus=403; ExpectCode=1403,1007 }
        @{ Id='CH-021'; Name='Admin change list with non-numeric tourId -> 400'; Severity='P1'; Method='GET'; Path='/booking/changes/admin?tourId=abc'; Token=$admin; ExpectStatus=400; ExpectCode=1400 }
        @{ Id='CH-022'; Name='Review missing change -> 404'; Severity='P1'; Method='POST'; Path='/booking/changes/999999/review'; Token=$admin; Body=@{ action='APPROVE'; expectedVersion=0 }; ExpectStatus=404; ExpectCode=1404 }
        @{ Id='CH-023'; Name='Review of a finished change is rejected -> 409'; Severity='P1'; Method='POST'; Path="/booking/changes/$changeId/review"; Token=$admin; Body=@{ action='TELEPORT'; expectedVersion=0 }; ExpectStatus=409; ExpectCode=1409; Note='a finished change is refused before the action is parsed'; SkipReason=$(if ($changeId) { '' } else { 'no change fixture' }) }
        @{ Id='CH-024'; Name='Review with a stale expectedVersion -> 409'; Severity='P0'; Method='POST'; Path="/booking/changes/$changeId/review"; Token=$admin; Body=@{ action='REJECT'; expectedVersion=999999 }; ExpectStatus=409; ExpectCode=1409; SkipReason=$(if ($changeId) { '' } else { 'no change fixture' }) }
        @{ Id='CH-025'; Name='Respond to missing change -> 404'; Severity='P1'; Method='POST'; Path='/booking/changes/999999/respond'; Token=$u1; Body=@{ action='ACCEPT'; expectedVersion=0 }; ExpectStatus=404; ExpectCode=1404 }
        @{ Id='CH-026'; Name='Refund missing change -> 404'; Severity='P1'; Method='POST'; Path='/booking/changes/999999/refund'; Token=$admin; Body=@{ amount=100000; reference='REF-1'; note='Hoan tien cho khach hang theo yeu cau' }; ExpectStatus=404; ExpectCode=1404 }
        @{ Id='CH-027'; Name='Refund as customer -> 403'; Severity='P0'; Method='POST'; Path="/booking/changes/$changeId/refund"; Token=$u1; Body=@{ amount=100000; reference='REF-1'; note='Hoan tien cho khach hang theo yeu cau' }; ExpectStatus=403; ExpectCode=1403,1007; SkipReason=$(if ($changeId) { '' } else { 'no change fixture' }) }
        @{ Id='CH-028'; Name='Activate missing shop change -> 404'; Severity='P1'; Method='POST'; Path='/booking/changes/shop/999999/activate'; Token=$admin; ExpectStatus=404; ExpectCode=1404 }
        @{ Id='CH-029'; Name='Activate shop change as customer -> 403'; Severity='P0'; Method='POST'; Path='/booking/changes/shop/1/activate'; Token=$u1; ExpectStatus=403; ExpectCode=1403,1007 }

        @{ Id='CH-030'; Name='Shop preview as customer -> 403'; Severity='P0'; Method='POST'; Path='/booking/changes/shop/preview'; Token=$u1; Body=$shopBody; ExpectStatus=403; ExpectCode=1403,1007 }
        @{ Id='CH-031'; Name='Shop preview without clientKey -> 400'; Severity='P1'; Method='POST'; Path='/booking/changes/shop/preview'; Token=$admin; Body=@{ sourceId=[int]$dep; expectedVersion=0; reasonCode='WEATHER'; reason='Thoi tiet xau khong the khoi hanh an toan'; evidence='Cong van so 123 nam 2026'; originalCannotDepart=$true; departureAt=$future; returnAt=$later; capacity=20; responseDeadline=$future }; ExpectStatus=400; ExpectCode=1001 }
        @{ Id='CH-032'; Name='Shop preview with short reason -> 400'; Severity='P1'; Method='POST'; Path='/booking/changes/shop/preview'; Token=$admin; Body=@{ clientKey=(New-Uuid); sourceId=[int]$dep; expectedVersion=0; reasonCode='WEATHER'; reason='ngan'; evidence='Cong van so 123 nam 2026'; originalCannotDepart=$true; departureAt=$future; returnAt=$later; capacity=20; responseDeadline=$future }; ExpectStatus=400; ExpectCode=1001 }
        @{ Id='CH-033'; Name='Shop preview with short evidence -> 400'; Severity='P1'; Method='POST'; Path='/booking/changes/shop/preview'; Token=$admin; Body=@{ clientKey=(New-Uuid); sourceId=[int]$dep; expectedVersion=0; reasonCode='WEATHER'; reason='Thoi tiet xau khong the khoi hanh an toan'; evidence='cv'; originalCannotDepart=$true; departureAt=$future; returnAt=$later; capacity=20; responseDeadline=$future }; ExpectStatus=400; ExpectCode=1001 }
        @{ Id='CH-034'; Name='Shop preview with a past replacement departure -> 409'; Severity='P1'; Method='POST'; Path='/booking/changes/shop/preview'; Token=$admin; Body=@{ clientKey=(New-Uuid); sourceId=[int]$dep; expectedVersion=$depVersion; reasonCode='NATURAL_DISASTER'; reason='Thoi tiet xau khong the khoi hanh an toan'; evidence='Cong van so 123 nam 2026'; originalCannotDepart=$true; departureAt=$past; returnAt=$later; capacity=20; responseDeadline=$future }; ExpectStatus=409; ExpectCode=1409 }
        @{ Id='CH-035'; Name='Shop preview with return before departure -> 409'; Severity='P1'; Method='POST'; Path='/booking/changes/shop/preview'; Token=$admin; Body=@{ clientKey=(New-Uuid); sourceId=[int]$dep; expectedVersion=$depVersion; reasonCode='NATURAL_DISASTER'; reason='Thoi tiet xau khong the khoi hanh an toan'; evidence='Cong van so 123 nam 2026'; originalCannotDepart=$true; departureAt=$later; returnAt=$future; capacity=20; responseDeadline=$future }; ExpectStatus=409; ExpectCode=1409 }
        @{ Id='CH-036'; Name='Shop preview with capacity 0 -> 400'; Severity='P1'; Method='POST'; Path='/booking/changes/shop/preview'; Token=$admin; Body=@{ clientKey=(New-Uuid); sourceId=[int]$dep; expectedVersion=0; reasonCode='WEATHER'; reason='Thoi tiet xau khong the khoi hanh an toan'; evidence='Cong van so 123 nam 2026'; originalCannotDepart=$true; departureAt=$future; returnAt=$later; capacity=0; responseDeadline=$future }; ExpectStatus=400; ExpectCode=1001 }
        @{ Id='CH-037'; Name='Shop create without responseDeadline -> 400'; Severity='P1'; Method='POST'; Path='/booking/changes/shop'; Token=$admin; Body=@{ clientKey=(New-Uuid); sourceId=[int]$dep; expectedVersion=0; reasonCode='WEATHER'; reason='Thoi tiet xau khong the khoi hanh an toan'; evidence='Cong van so 123 nam 2026'; originalCannotDepart=$true; departureAt=$future; returnAt=$later; capacity=20 }; ExpectStatus=400; ExpectCode=1001 }
        @{ Id='CH-038'; Name='Shop create for missing departure -> 404'; Severity='P1'; Method='POST'; Path='/booking/changes/shop'; Token=$admin; Body=@{ clientKey=(New-Uuid); sourceId=999999; expectedVersion=0; reasonCode='WEATHER'; reason='Thoi tiet xau khong the khoi hanh an toan'; evidence='Cong van so 123 nam 2026'; originalCannotDepart=$true; departureAt=$future; returnAt=$later; capacity=20; responseDeadline=$future }; ExpectStatus=404; ExpectCode=1404 }
    )
    Run-CaseSet -Group $g -Cases $cases
}

# ---------------- Group 7: payment / reconciliation / invoice ----------------
function Initialize-VnPayFixture {
    # A pending-payment hold lives only 15 minutes, so a cached one may already have expired.
    # Try the cached booking first, then provision fresh ones until a checkout URL is produced.
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        $bookingId = ''
        if ($attempt -eq 1 -and $script:State['errPendingBookingId']) {
            $bookingId = [string]$script:State['errPendingBookingId']
        }
        else {
            $bookingId = New-PendingBooking -Fresh
        }
        if (-not $bookingId) { continue }
        $quote = Invoke-Http -Method GET -Path "/payment/vnpay/bookings/$bookingId/quote" -Token (Get-Token 'user1Token')
        if ($quote.Status -ne 200 -or $null -eq $quote.Json.result) { continue }
        $amountValue = $quote.Json.result.amount
        if ($null -eq $amountValue) { $amountValue = $quote.Json.result.amountVnd }
        $amount = [long]$amountValue
        if ($amount -le 0) { continue }
        $create = Invoke-Http -Method POST -Path '/payment/vnpay/bookings' -Token (Get-Token 'user1Token') -Body @{ bookingId = [int]$bookingId; expectedAmount = [string]$amount }
        if ($create.Status -ne 200) { continue }
        $query = $create.Text
        $idx = $query.IndexOf('?')
        if ($idx -ge 0) { $query = $query.Substring($idx + 1) }
        $parsed = [System.Web.HttpUtility]::ParseQueryString($query)
        $ref = $parsed['vnp_TxnRef']
        if (-not $ref) { continue }
        $script:State['errPendingBookingId'] = $bookingId
        $script:State['errVnpayRef'] = $ref
        $script:State['errVnpayAmount'] = [string]$amount
        $script:State['errVnpayBookingId'] = $bookingId
        Save-State
        return @{ TxnRef = $ref; Amount = $amount }
    }
    return $null
}

function New-VnPayIpnQuery {
    param([string]$TxnRef, [long]$Amount, [string]$ResponseCode = '00', [string]$TxnStatus = '00', [switch]$BreakSignature)
    $secret = Get-VnPaySecret
    $fields = @{
        vnp_Amount = [string]($Amount * 100)
        vnp_BankCode = 'NCB'
        vnp_ResponseCode = $ResponseCode
        vnp_TransactionStatus = $TxnStatus
        vnp_TmnCode = 'TESTTMN01'
        vnp_TxnRef = $TxnRef
        vnp_PayDate = (Get-Date).ToString('yyyyMMddHHmmss')
        vnp_TransactionNo = '14000000'
        vnp_OrderInfo = 'Negative test order'
    }
    $signed = New-VnPaySignature -Fields $fields -Secret $secret
    if ($BreakSignature) { $signed = $signed.Substring(0, $signed.Length - 8) + 'deadbeef' }
    return $signed
}

function Run-PaymentInvoice {
    $g = 'payment-invoice'
    $admin = Get-Token 'adminToken'
    $u1 = Get-Token 'user1Token'
    $u5 = Get-Token 'user5Token'
    $own = [string]$script:Fixtures['ownBooking']
    $other = [string]$script:Fixtures['otherBooking']

    $vnp = $null
    try { $vnp = Initialize-VnPayFixture } catch { $vnp = $null }
    $ref = if ($vnp) { $vnp.TxnRef } else { '' }
    $amount = if ($vnp) { $vnp.Amount } else { 0 }

    $invoiceId = ''
    if ($own) {
        $inv = Get-JsonResult -Path "/invoice/direct/booking/$own" -Token $admin
        if ($null -ne $inv -and $null -ne $inv.id) { $invoiceId = [string]$inv.id }
    }

    $cases = @(
        @{ Id='PY-001'; Name='VNPay quote without token -> 401'; Severity='P0'; Method='GET'; Path="/payment/vnpay/bookings/$own/quote"; ExpectStatus=401; ExpectCode=1401 }
        @{ Id='PY-002'; Name='VNPay quote for missing booking -> 404'; Severity='P1'; Method='GET'; Path='/payment/vnpay/bookings/999999/quote'; Token=$u1; ExpectStatus=404; ExpectCode=1404 }
        @{ Id='PY-003'; Name='VNPay quote for another booking -> 403'; Severity='P0'; Method='GET'; Path="/payment/vnpay/bookings/$other/quote"; Token=$u1; ExpectStatus=403; ExpectCode=1403; SkipReason=$(if ($other) { '' } else { 'no other booking fixture' }) }
        @{ Id='PY-004'; Name='VNPay quote with non-numeric id -> 400'; Severity='P1'; Method='GET'; Path='/payment/vnpay/bookings/abc/quote'; Token=$u1; ExpectStatus=400; ExpectCode=1400 }

        @{ Id='PY-010'; Name='VNPay create without token -> 401'; Severity='P0'; Method='POST'; Path='/payment/vnpay/bookings'; Body=@{ bookingId=1 }; ExpectStatus=401; ExpectCode=1401 }
        @{ Id='PY-011'; Name='VNPay create without bookingId -> 400'; Severity='P1'; Method='POST'; Path='/payment/vnpay/bookings'; Token=$u1; Body=@{}; ExpectStatus=400 }
        @{ Id='PY-012'; Name='VNPay create with bookingId 0 -> 400'; Severity='P1'; Method='POST'; Path='/payment/vnpay/bookings'; Token=$u1; Body=@{ bookingId=0 }; ExpectStatus=400 }
        @{ Id='PY-013'; Name='VNPay create for missing booking -> 404'; Severity='P1'; Method='POST'; Path='/payment/vnpay/bookings'; Token=$u1; Body=@{ bookingId=999999 }; ExpectStatus=404 }
        @{ Id='PY-014'; Name='VNPay create for another booking -> 403'; Severity='P0'; Method='POST'; Path='/payment/vnpay/bookings'; Token=$u1; Body=@{ bookingId=[int]$other }; ExpectStatus=403; SkipReason=$(if ($other) { '' } else { 'no other booking fixture' }) }
        @{ Id='PY-015'; Name='VNPay create with mismatched amount -> 400'; Severity='P0'; Method='POST'; Path='/payment/vnpay/bookings'; Token=$u1; Body=@{ bookingId=[int]$script:State['errVnpayBookingId']; expectedAmount='1' }; ExpectStatus=400; SkipReason=$(if ($script:State['errVnpayBookingId']) { '' } else { 'no pending booking fixture' }) }

        @{ Id='PY-020'; Name='VNPay IPN without signature -> RspCode 97'; Severity='P0'; Method='GET'; Path='/payment/vnpay/ipn'; ExpectStatus=200; ExpectCode=97; CodePath='RspCode' }
        @{ Id='PY-021'; Name='VNPay IPN with broken signature -> RspCode 97'; Severity='P0'; Method='GET'; Path=('/payment/vnpay/ipn?' + (New-VnPayIpnQuery -TxnRef 'UNKNOWNREF' -Amount 100000 -BreakSignature)); ExpectStatus=200; ExpectCode=97; CodePath='RspCode' }
        @{ Id='PY-022'; Name='VNPay IPN unknown txnRef -> RspCode 01'; Severity='P1'; Method='GET'; Path=('/payment/vnpay/ipn?' + (New-VnPayIpnQuery -TxnRef 'NOPE-2026-0001' -Amount 100000)); ExpectStatus=200; ExpectCode=1; CodePath='RspCode'; Note='01 = order not found' }
        @{ Id='PY-023'; Name='VNPay IPN amount mismatch -> RspCode 04'; Severity='P0'; Method='GET'; Path=('/payment/vnpay/ipn?' + (New-VnPayIpnQuery -TxnRef $ref -Amount ($amount + 1000))); ExpectStatus=200; ExpectCode=4; CodePath='RspCode'; SkipReason=$(if ($ref) { '' } else { 'no pending VNPay transaction' }) }
        @{ Id='PY-024'; Name='VNPay IPN with non-numeric amount -> RspCode 04'; Severity='P1'; Method='GET'; Path=('/payment/vnpay/ipn?' + (New-VnPayIpnQuery -TxnRef $ref -Amount 0)); ExpectStatus=200; ExpectCode=4,99; CodePath='RspCode'; SkipReason=$(if ($ref) { '' } else { 'no pending VNPay transaction' }) }
        @{ Id='PY-025'; Name='VNPay return with broken signature is a client error'; Severity='P1'; Method='GET'; Path=('/payment/vnpay/bookings/return?' + (New-VnPayIpnQuery -TxnRef 'UNKNOWNREF' -Amount 100000 -BreakSignature)); ExpectStatus=400; ExpectCode=1400 }

        @{ Id='PY-030'; Name='MoMo quote without token -> 401'; Severity='P0'; Method='GET'; Path="/payment/momo/bookings/$own/quote"; ExpectStatus=401; ExpectCode=1401 }
        @{ Id='PY-031'; Name='MoMo quote for missing booking -> 404'; Severity='P1'; Method='GET'; Path='/payment/momo/bookings/999999/quote'; Token=$u1; ExpectStatus=404; ExpectCode=1404 }
        @{ Id='PY-032'; Name='MoMo quote for another booking -> 403'; Severity='P0'; Method='GET'; Path="/payment/momo/bookings/$other/quote"; Token=$u1; ExpectStatus=403; ExpectCode=1403; SkipReason=$(if ($other) { '' } else { 'no other booking fixture' }) }
        @{ Id='PY-033'; Name='MoMo create without bookingId -> 400'; Severity='P1'; Method='POST'; Path='/payment/momo/bookings'; Token=$u1; Body=@{}; ExpectStatus=400 }
        @{ Id='PY-034'; Name='MoMo create while unconfigured -> 503'; Severity='P1'; Method='POST'; Path='/payment/momo/bookings'; Token=$u1; Body=@{ bookingId=[int]$own; expectedAmount='100000' }; ExpectStatus=503 }
        @{ Id='PY-035'; Name='MoMo IPN with empty body -> status 99'; Severity='P1'; Method='POST'; Path='/payment/momo/ipn'; Body=@{}; ExpectStatus=200; ExpectTextLike='*99*' }
        @{ Id='PY-036'; Name='MoMo return with an unsigned/garbage query -> 400'; Severity='P1'; Method='GET'; Path='/payment/momo/bookings/return?foo=bar'; ExpectStatus=400; ExpectCode=1400 }

        @{ Id='PY-040'; Name='Reconciliation list without token -> 401'; Severity='P0'; Method='GET'; Path='/payment/reconciliation'; ExpectStatus=401; ExpectCode=1401 }
        @{ Id='PY-041'; Name='Reconciliation list as customer -> 403'; Severity='P0'; Method='GET'; Path='/payment/reconciliation'; Token=$u1; ExpectStatus=403; ExpectCode=1403 }
        @{ Id='PY-042'; Name='Reconciliation page=-1 -> 400'; Severity='P1'; Method='GET'; Path='/payment/reconciliation?page=-1'; Token=$admin; ExpectStatus=400; ExpectCode=1400 }
        @{ Id='PY-043'; Name='Reconciliation page=100001 -> 400'; Severity='P1'; Method='GET'; Path='/payment/reconciliation?page=100001'; Token=$admin; ExpectStatus=400; ExpectCode=1400 }
        @{ Id='PY-044'; Name='Reconciliation unknown view -> 400'; Severity='P1'; Method='GET'; Path='/payment/reconciliation?view=BAD'; Token=$admin; ExpectStatus=400; ExpectCode=1400 }
        @{ Id='PY-045'; Name='Reconciliation history of unknown ref is empty'; Severity='P2'; Method='GET'; Path='/payment/reconciliation/NOPE-2026-0001/history'; Token=$admin; ExpectStatus=200; Note='observation: unknown ref yields an empty history, not 404' }
        @{ Id='PY-046'; Name='Reconciliation resolve of unknown ref -> 404'; Severity='P1'; Method='POST'; Path='/payment/reconciliation/NOPE-2026-0001/resolve'; Token=$admin; Body=@{ action='PAYMENT_FOUND'; amountVnd=100000; reference='REF-1'; note='Negative test reconciliation note'; confirmed=$true }; ExpectStatus=404; ExpectCode=1404 }
        @{ Id='PY-047'; Name='Reconciliation resolve as customer -> 403'; Severity='P0'; Method='POST'; Path='/payment/reconciliation/NOPE-2026-0001/resolve'; Token=$u1; Body=@{ action='PAYMENT_FOUND'; amountVnd=100000; reference='REF-1'; note='Negative test reconciliation note'; confirmed=$true }; ExpectStatus=403; ExpectCode=1403 }
        @{ Id='PY-048'; Name='Reconciliation resolve without token -> 401'; Severity='P0'; Method='POST'; Path='/payment/reconciliation/NOPE-2026-0001/resolve'; Body=@{ action='MARK_PAID'; amount=100000; note='Negative test reconciliation note' }; ExpectStatus=401; ExpectCode=1401 }
        @{ Id='PY-049'; Name='Reconciliation resolve with unknown action -> 400'; Severity='P1'; Method='POST'; Path='/payment/reconciliation/NOPE-2026-0001/resolve'; Token=$admin; Body=@{ action='DROP_TABLE'; amount=100000; note='Negative test reconciliation note' }; ExpectStatus=400,409; ExpectCode=1400,1404,1409 }

        @{ Id='PY-050'; Name='Invoice by missing id -> 404'; Severity='P1'; Method='GET'; Path='/invoice/direct/999999'; Token=$admin; ExpectStatus=404; ExpectCode=1404 }
        @{ Id='PY-051'; Name='Invoice with non-numeric id -> 400'; Severity='P1'; Method='GET'; Path='/invoice/direct/abc'; Token=$admin; ExpectStatus=400; ExpectCode=1400 }
        @{ Id='PY-052'; Name='Invoice by missing booking -> 404'; Severity='P1'; Method='GET'; Path='/invoice/direct/booking/999999'; Token=$admin; ExpectStatus=404; ExpectCode=1404 }
        @{ Id='PY-053'; Name='Customer reads an invoice that is not theirs -> 403'; Severity='P0'; Method='GET'; Path="/invoice/direct/$invoiceId"; Token=$u5; ExpectStatus=403; ExpectCode=1007,1403; SkipReason=$(if ($invoiceId) { '' } else { 'no invoice fixture' }) }
        @{ Id='PY-054'; Name='Customer invoice list is scoped to their account'; Severity='P0'; Method='GET'; Path='/invoice/direct/all'; Token=$u1; ExpectStatus=200; Validate={ param($rec,$resp) $foreign = @($resp.Json.result) | Where-Object { [string]$_.accountId -ne [string]$script:State['user1Id'] }; if (@($foreign).Count -gt 0) { return 'list leaked invoices of another account' }; return $true } }
        @{ Id='PY-055'; Name='Admin invoice list page=-1 is clamped'; Severity='P2'; Method='GET'; Path='/invoice/direct/all?page=-1'; Token=$admin; ExpectStatus=200; Note='observation: paging is clamped here, other services reject bad paging' }
        @{ Id='PY-056'; Name='Create invoice as customer -> 403'; Severity='P0'; Method='POST'; Path='/invoice/direct/create'; Token=$u1; Body=@{ bookingRequest=@{ bookingId='1'; tourDepartureId='1'; userId='1'; listOfCustomers=@(@{ fullName='A'; birthday='1990-01-01'; customerType='ADULT' }) } }; ExpectStatus=403; ExpectCode=1007,1403 }
        @{ Id='PY-057'; Name='Create invoice without bookingRequest -> 400'; Severity='P1'; Method='POST'; Path='/invoice/direct/create'; Token=$admin; Body=@{}; ExpectStatus=400; ExpectCode=1001 }
        @{ Id='PY-058'; Name='Create invoice for missing booking -> 404'; Severity='P1'; Method='POST'; Path='/invoice/direct/create'; Token=$admin; Body=@{ bookingRequest=@{ bookingId='999999'; tourDepartureId='1'; userId='1'; listOfCustomers=@(@{ fullName='A'; birthday='1990-01-01'; customerType='ADULT' }) } }; ExpectStatus=404; ExpectCode=1014,1404,1012 }
    )
    Run-CaseSet -Group $g -Cases $cases

    if ($vnp) {
        $first = Invoke-Http -Method GET -Path ('/payment/vnpay/ipn?' + (New-VnPayIpnQuery -TxnRef $vnp.TxnRef -Amount $vnp.Amount))
        $firstCode = if ($null -ne $first.Json) { [string]$first.Json.RspCode } else { '' }
        $state = if ($first.Status -eq 200 -and $firstCode -eq '00') { 'PASS' } else { 'FAIL' }
        Add-ManualResult -Group $g -Id 'PY-026' -Name 'VNPay IPN confirms a pending transaction' -Method 'GET' -Path '/payment/vnpay/ipn' -State $state -ExpectedStatus '200' -ExpectedCode '00' -Status $first.Status -ActualCode $firstCode -Response $first.Text -Note "txnRef=$($vnp.TxnRef)" | Out-Null

        $second = Invoke-Http -Method GET -Path ('/payment/vnpay/ipn?' + (New-VnPayIpnQuery -TxnRef $vnp.TxnRef -Amount $vnp.Amount))
        $secondCode = if ($null -ne $second.Json) { [string]$second.Json.RspCode } else { '' }
        $state2 = if ($second.Status -eq 200 -and $secondCode -eq '02') { 'PASS' } else { 'FAIL' }
        Add-ManualResult -Group $g -Id 'PY-027' -Name 'VNPay IPN replay after confirmation -> RspCode 02' -Method 'GET' -Path '/payment/vnpay/ipn' -State $state2 -ExpectedStatus '200' -ExpectedCode '02' -Status $second.Status -ActualCode $secondCode -Response $second.Text -Note 'idempotent replay' | Out-Null
    }
}

# ---------------- Group 8: search / file / AI / socket / infra ----------------
function Run-SearchFileAi {
    $g = 'search-file-ai'
    $admin = Get-Token 'adminToken'
    $u1 = Get-Token 'user1Token'
    $u5 = Get-Token 'user5Token'
    $tourId = if ($script:State['tourId']) { [string]$script:State['tourId'] } else { '1' }
    $svgBytes = [System.Text.Encoding]::UTF8.GetBytes('<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>')
    $pngBytes = [byte[]](0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D)
    $aiConversation = ('a' * 43)
    $aiRequest = (New-Uuid)

    $cases = @(
        @{ Id='SF-001'; Name='Search page=-1 -> 400/503'; Severity='P1'; Method='GET'; Path='/search/tours?page=-1'; ExpectStatus=400,503; ExpectCode=1400,1503 }
        @{ Id='SF-002'; Name='Search size=0 -> 400/503'; Severity='P1'; Method='GET'; Path='/search/tours?size=0'; ExpectStatus=400,503; ExpectCode=1400,1503 }
        @{ Id='SF-003'; Name='Search size=1000 -> 400/503'; Severity='P1'; Method='GET'; Path='/search/tours?size=1000'; ExpectStatus=400,503; ExpectCode=1400,1503 }
        @{ Id='SF-004'; Name='Search with malformed basePrice -> 400/503'; Severity='P2'; Method='GET'; Path='/search/tours/search?basePrice=abc'; ExpectStatus=400,503; ExpectCode=1400,1503 }
        @{ Id='SF-005'; Name='Search sync without token -> 401'; Severity='P0'; Method='POST'; Path='/search/tours/sync'; ExpectStatus=401; ExpectCode=1401 }
        @{ Id='SF-006'; Name='Search sync as customer -> 403/503'; Severity='P0'; Method='POST'; Path='/search/tours/sync'; Token=$u1; ExpectStatus=403,503; ExpectCode=1007,1403,1503 }
        @{ Id='SF-007'; Name='Catalog snapshot without token -> 401'; Severity='P1'; Method='GET'; Path='/tour/tours/search-snapshot'; ExpectStatus=401; ExpectCode=1401 }

        @{ Id='SF-010'; Name='Download unknown file -> 404'; Severity='P1'; Method='GET'; Path='/file/media/download/does-not-exist.png'; ExpectStatus=404; ExpectCode=1012,1404 }
        @{ Id='SF-011'; Name='Download path traversal is refused at the edge'; Severity='P0'; Method='GET'; Path='/file/media/download/..%2F..%2Fapplication.properties'; ExpectStatus=400,401,404; ExpectCode=1401,1400,1012,1404; Note='encoded separators leave the public prefix, so the gateway answers 401' }
        @{ Id='SF-012'; Name='Media lookup without tourIds -> 400'; Severity='P1'; Method='GET'; Path='/file/media/tours'; ExpectStatus=400; ExpectCode=1400 }
        @{ Id='SF-013'; Name='Owner lookup for unknown id -> 404'; Severity='P2'; Method='GET'; Path='/file/owner/999999'; ExpectStatus=404; ExpectCode=1012,1404 }
        @{ Id='SF-014'; Name='Orphan media list as customer -> 403'; Severity='P0'; Method='GET'; Path='/file/media/tour-orphans'; Token=$u1; ExpectStatus=403 }
        @{ Id='SF-015'; Name='Delete missing orphan as admin -> 400'; Severity='P1'; Method='DELETE'; Path='/file/media/tour-orphans/not-there'; Token=$admin; ExpectStatus=400,404; ExpectCode=1400,1012,1404; Note='observation: this endpoint reports a missing orphan as 400' }
        @{ Id='SF-016'; Name='Tour upload as JSON is refused'; Severity='P2'; Method='POST'; Path="/file/media/uploadTour/$tourId"; Token=$admin; Body=@{ files=@() }; ExpectStatus=400,415; ExpectCode=1400,1415; Note='observation: file-service answers 400 where other services answer 415' }
        @{ Id='SF-017'; Name='Tour upload without file part -> 400'; Severity='P2'; Method='POST'; Path="/file/media/uploadTour/$tourId"; Token=$admin; Form=@{ note='x' }; ExpectStatus=400; ExpectCode=1400 }
        @{ Id='SF-018'; Name='Tour upload SVG -> 400'; Severity='P2'; Method='POST'; Path="/file/media/uploadTour/$tourId"; Token=$admin; Files=@{ files=(New-FileSpec 'evil.svg' 0 'image/svg+xml' $svgBytes) }; ExpectStatus=400; ExpectCode=1400,1413 }
        @{ Id='SF-019'; Name='Tour upload over 5MB -> 413'; Severity='P2'; Method='POST'; Path="/file/media/uploadTour/$tourId"; Token=$admin; Files=@{ files=(New-FileSpec 'big.png' (6 * 1024 * 1024) 'image/png' $pngBytes) }; ExpectStatus=413; ExpectCode=1413 }
        @{ Id='SF-020'; Name='Avatar upload for another account -> 403'; Severity='P0'; Method='POST'; Path='/auth/users/updateAvtUser/' + $script:State['user5Id']; Token=$u1; Files=@{ file=(New-FileSpec 'a.png' 1024 'image/png' $pngBytes) }; ExpectStatus=403; ExpectCode=1007,1403 }
        @{ Id='SF-021'; Name='Location image upload as customer -> 403'; Severity='P0'; Method='POST'; Path='/file/media/upload/location'; Token=$u1; Form=@{ locationId='1' }; Files=@{ file=(New-FileSpec 'a.png' 0 'image/png' $pngBytes) }; ExpectStatus=403; ExpectCode=1007 }
        @{ Id='SF-022'; Name='Direct avatar upload for another account -> 403'; Severity='P0'; Method='POST'; Path='/file/media/uploadAvt/' + $script:State['user5Id']; Token=$u1; Files=@{ file=(New-FileSpec 'a.png' 0 'image/png' $pngBytes) }; ExpectStatus=403; ExpectCode=1007 }
        @{ Id='SF-023'; Name='Gallery edit as customer -> 403'; Severity='P0'; Method='POST'; Path="/file/media/tour/$tourId/edit"; Token=$u1; Form=@{ changes='[]' }; Files=@{ files=(New-FileSpec 'a.png' 0 'image/png' $pngBytes) }; ExpectStatus=403; ExpectCode=1007 }
        @{ Id='SF-024'; Name='Gallery edit with a malformed changes list -> 400'; Severity='P1'; Method='POST'; Path="/file/media/tour/$tourId/edit"; Token=$admin; Form=@{ changes='not-json' }; ExpectStatus=400; ExpectCode=1400 }
        @{ Id='SF-025'; Name='Location image upload without the file part -> 400'; Severity='P1'; Method='POST'; Path='/file/media/upload/location'; Token=$admin; Form=@{ locationId='1' }; ExpectStatus=400; ExpectCode=1400 }

        @{ Id='SF-030'; Name='AI conversations with malformed JSON -> 400 INVALID_REQUEST'; Severity='P1'; Method='POST'; Path='/ai/conversations'; RawBody='{"x":'; ExpectStatus=400; ExpectCode=4000; ExpectErrorType='INVALID_REQUEST' }
        @{ Id='SF-031'; Name='AI conversations with text/plain -> 415'; Severity='P2'; Method='POST'; Path='/ai/conversations'; RawBody='x'; ContentType='text/plain'; ExpectStatus=415; Note='observation: media-type failure is answered by Spring defaults, not the AI envelope' }
        @{ Id='SF-032'; Name='AI reset of an unknown conversation -> 410 CONVERSATION_EXPIRED'; Severity='P1'; Method='POST'; Path='/ai/conversations/reset'; Body=@{ conversationId=$aiConversation }; ExpectStatus=410; ExpectCode=4100; ExpectErrorType='CONVERSATION_EXPIRED' }
        @{ Id='SF-033'; Name='AI reset without conversationId -> 400'; Severity='P1'; Method='POST'; Path='/ai/conversations/reset'; Body=@{}; ExpectStatus=400; ExpectCode=4000; ExpectErrorType='INVALID_REQUEST' }
        @{ Id='SF-034'; Name='AI chat without conversationId -> 400'; Severity='P1'; Method='POST'; Path='/ai/chat'; Body=@{ requestId=$aiRequest; message='Xin chao' }; ExpectStatus=400; ExpectCode=4000; ExpectErrorType='INVALID_REQUEST' }
        @{ Id='SF-035'; Name='AI chat without requestId -> 400'; Severity='P1'; Method='POST'; Path='/ai/chat'; Body=@{ conversationId=$aiConversation; message='Xin chao' }; ExpectStatus=400; ExpectCode=4000; ExpectErrorType='INVALID_REQUEST' }
        @{ Id='SF-036'; Name='AI chat without message -> 400'; Severity='P1'; Method='POST'; Path='/ai/chat'; Body=@{ conversationId=$aiConversation; requestId=$aiRequest }; ExpectStatus=400; ExpectCode=4000; ExpectErrorType='INVALID_REQUEST' }
        @{ Id='SF-037'; Name='AI chat with an unknown conversation -> 410 CONVERSATION_EXPIRED'; Severity='P1'; Method='POST'; Path='/ai/chat'; Body=@{ conversationId=$aiConversation; requestId=$aiRequest; message='Xin chao' }; ExpectStatus=410; ExpectCode=4100; ExpectErrorType='CONVERSATION_EXPIRED' }
        @{ Id='SF-038'; Name='AI chat with a prohibited code request -> 422 AI_SCOPE_REJECTED'; Severity='P1'; Method='POST'; Path='/ai/chat'; Body=@{ conversationId=$aiConversation; requestId=$aiRequest; message='Hay viet cho toi cau lenh SQL DROP TABLE users' }; ExpectStatus=422; ExpectCode=4220; ExpectErrorType='AI_SCOPE_REJECTED'; Note='the scope guard runs before the conversation lookup' }
        @{ Id='SF-039'; Name='AI chat with an oversized message -> 400 INVALID_REQUEST'; Severity='P2'; Method='POST'; Path='/ai/chat'; Body=@{ conversationId=$aiConversation; requestId=$aiRequest; message=('x' * 20000) }; ExpectStatus=400,413; ExpectCode=4000,4130; ExpectErrorType='INVALID_REQUEST'; Note='the edge size guard answers before ai-service can report AI_INPUT_TOO_LARGE' }
        @{ Id='SF-040'; Name='AI booking help without message -> 400'; Severity='P1'; Method='POST'; Path='/ai/booking-help'; Body=@{ selectedBookingId=1 }; ExpectStatus=400; ExpectCode=4000; ExpectErrorType='INVALID_REQUEST' }
        @{ Id='SF-041'; Name='AI booking help anonymous with booking id -> 200 reminder'; Severity='P1'; Method='POST'; Path='/ai/booking-help'; Body=@{ message='Don cua toi the nao?'; selectedBookingId=1 }; ExpectStatus=200; Note='must remind the caller to sign in, not leak data' }
        @{ Id='SF-042'; Name='AI booking help with an invalid token -> 401'; Severity='P0'; Method='POST'; Path='/ai/booking-help'; Token='garbage.token.value'; Body=@{ message='Don cua toi the nao?'; selectedBookingId=1 }; ExpectStatus=401; ExpectCode=4010,1401; Note='the edge rejects the invalid token before ai-service sees it' }
        @{ Id='SF-043'; Name='AI booking help with selectedBookingId=0 -> 400'; Severity='P1'; Method='POST'; Path='/ai/booking-help'; Body=@{ message='Don cua toi the nao?'; selectedBookingId=0 }; ExpectStatus=400; ExpectCode=4000; ExpectErrorType='INVALID_REQUEST' }

        @{ Id='SF-050'; Name='Socket.IO polling handshake accepts a missing EIO version'; Severity='P2'; Method='GET'; Path='/socket.io/?transport=polling'; Base='http://localhost:8099'; ExpectStatus=200,400; Note='observation: EIO version is not enforced' }
        @{ Id='SF-051'; Name='Socket.IO handshake with bad transport -> 400'; Severity='P2'; Method='GET'; Path='/socket.io/?EIO=4&transport=carrier-pigeon'; Base='http://localhost:8099'; ExpectStatus=400,401 }
        @{ Id='SF-052'; Name='Socket.IO handshake from unknown origin -> 401'; Severity='P1'; Method='GET'; Path='/socket.io/?EIO=4&transport=polling'; Base='http://localhost:8099'; Headers=@{ Origin='https://evil.example' }; ExpectStatus=401,403 }
        @{ Id='SF-044'; Name='AI health reports the disabled model'; Severity='P2'; Method='GET'; Path='http://localhost:8091/ai/health'; ExpectStatus=200; Validate={ param($rec,$resp) if (@('DISABLED','UP') -notcontains [string]$resp.Json.result.status) { return ('unexpected AI status ' + $resp.Json.result.status) }; return $true } }
        @{ Id='SF-045'; Name='AI policy catalogue is reachable anonymously'; Severity='P2'; Method='GET'; Path='/ai/policies'; ExpectStatus=200; Validate={ param($rec,$resp) if ($null -eq $resp.Json.result) { return 'missing policy catalogue' }; return $true } }
    )
    Run-CaseSet -Group $g -Cases $cases

    if ($IncludeRateLimit) {
        # file-service keeps its own per-IP download budget (60/min by default) and answers the
        # over-budget request with 429 + Retry-After and no body.
        $downloadPath = '/file/media/download/does-not-exist.png'
        $hit = $null
        for ($i = 0; $i -lt 90; $i++) {
            $r = Invoke-Http -Method GET -Path $downloadPath -Headers @{ 'X-Real-IP' = '10.99.0.7' }
            if ($r.Status -eq 429) { $hit = $r; break }
        }
        $retry = if ($hit -and $hit.Headers.ContainsKey('Retry-After')) { $hit.Headers['Retry-After'] } else { '' }
        $ok = ($null -ne $hit) -and (-not [string]::IsNullOrWhiteSpace($retry))
        Add-ManualResult -Group $g -Id 'SF-060' -Name 'File download throttle -> 429 + Retry-After' -Method 'GET' -Path $downloadPath `
            -State $(if ($ok) { 'PASS' } else { 'FAIL' }) -ExpectedStatus '429' -ExpectedCode 'n/a (empty body)' `
            -Status $(if ($hit) { $hit.Status } else { '' }) -ActualCode '' `
            -Response $(if ($hit) { "attempts=$i retry-after=$retry" } else { 'no 429 within 90 requests' }) -Note 'opt-in' | Out-Null

        # ai-service budgets are enforced at the gateway; the throttled gateway has create<=2 / 30 min.
        $hit = $null
        for ($i = 0; $i -lt 10; $i++) {
            $r = Invoke-Http -Method POST -Path '/ai/conversations' -Base $script:State['rateGateway']
            if ($r.Status -eq 429) { $hit = $r; break }
        }
        $code = if ($hit -and $null -ne $hit.Json) { [string]$hit.Json.code } else { '' }
        $type = if ($hit -and $null -ne $hit.Json.error) { [string]$hit.Json.error.type } else { '' }
        $retry = if ($hit -and $hit.Headers.ContainsKey('Retry-After')) { $hit.Headers['Retry-After'] } else { '' }
        $ok = ($null -ne $hit) -and ($code -in @('1429', '4290')) -and (-not [string]::IsNullOrWhiteSpace($retry))
        Add-ManualResult -Group $g -Id 'SF-061' -Name 'AI conversation creation throttle -> 429' -Method 'POST' -Path '/ai/conversations' `
            -State $(if ($ok) { 'PASS' } else { 'FAIL' }) -ExpectedStatus '429' -ExpectedCode '1429/4290' `
            -Status $(if ($hit) { $hit.Status } else { '' }) -ActualCode $code `
            -Response $(if ($hit) { $hit.Text } else { 'no 429 within 10 creates' }) -Note ("attempts=$($i + 1) retry-after=$retry type=$type") | Out-Null
    }
    else {
        Add-ManualResult -Group $g -Id 'SF-060' -Name 'File download throttle -> 429' -Method 'GET' -Path '/file/media/download/x' -State 'SKIP' -ExpectedStatus '429' -ExpectedCode '1429' -Status '' -ActualCode '' -Response 'run with -IncludeRateLimit' -Note 'opt-in' | Out-Null
        Add-ManualResult -Group $g -Id 'SF-061' -Name 'AI conversation creation throttle -> 429' -Method 'POST' -Path '/ai/conversations' -State 'SKIP' -ExpectedStatus '429' -ExpectedCode '4290' -Status '' -ActualCode '' -Response 'run with -IncludeRateLimit' -Note 'opt-in' | Out-Null
    }

    Add-ManualResult -Group $g -Id 'SF-070' -Name 'Auth breaker 503 while identity service is down' -Method 'GET' -Path '/auth/users/list' -State 'SKIP' -ExpectedStatus '503' -ExpectedCode '1503' -Status '' -ActualCode '' -Response 'run with -IncludeInfra and stop auth_service briefly' -Note 'destructive infra test' | Out-Null
}

# ---------------- dispatch ----------------
function Save-Results {
    foreach ($grp in $script:ResultsByGroup.Keys) {
        $path = Join-Path $logDir ("results-err-{0}.json" -f $grp)
        $json = $script:ResultsByGroup[$grp] | ConvertTo-Json -Depth 8
        [IO.File]::WriteAllText($path, $json, [System.Text.UTF8Encoding]::new($false))
        Write-Host ("[log] {0}" -f $path)
    }
}

try {
    Ensure-Tokens
    Resolve-Fixtures

    switch ($Group) {
        'gw-auth' { Run-GwAuth }
        'user-role' { Run-UserRole }
        'tour-location' { Run-TourLocation }
        'pricing-departure' { Run-PricingDeparture }
        'booking' { Run-Booking }
        'change' { Run-Change }
        'payment-invoice' { Run-PaymentInvoice }
        'search-file-ai' { Run-SearchFileAi }
        'all' {
            Run-GwAuth
            Run-UserRole
            Run-TourLocation
            Run-PricingDeparture
            Run-Booking
            Run-Change
            Run-PaymentInvoice
            Run-SearchFileAi
        }
    }
}
finally {
    Save-Results
    Write-Host ''
    Write-Host ("TOTAL: {0} PASS, {1} FAIL, {2} SKIP" -f $script:Counts.Pass, $script:Counts.Fail, $script:Counts.Skip)
}

if ($script:Counts.Fail -gt 0) { exit 1 }
exit 0
