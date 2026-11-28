param(
    [ValidateSet('auth','tour','booking','extra','all')][string]$Group = 'all',
    [switch]$StopOnFail
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
Add-Type -AssemblyName System.Web
Add-Type -AssemblyName System.Net.Http

$here       = Split-Path -Parent $MyInvocation.MyCommand.Path
$logDir     = Join-Path $here 'logs'
$statePath  = Join-Path $logDir 'state.properties'
$gateway    = 'http://localhost:8888/api/v1'
$script:Http = [System.Net.Http.HttpClient]::new()
$script:Http.Timeout = [TimeSpan]::FromSeconds(45)
$script:ResultsByGroup = @{}

# ---------------- state ----------------
$script:State = @{}
if (Test-Path $statePath) {
    foreach ($line in Get-Content $statePath) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $idx = $line.IndexOf('=')
        if ($idx -lt 1) { continue }
        $script:State[$line.Substring(0, $idx)] = $line.Substring($idx + 1)
    }
}
function Save-State {
    $lines = $script:State.Keys | Sort-Object | ForEach-Object { "$_=$($script:State[$_])" }
    [IO.File]::WriteAllText($statePath, ($lines -join "`r`n"), [System.Text.UTF8Encoding]::new($false))
}

# ---------------- core call ----------------
function Invoke-Api {
    param(
        [string]$Group,
        [string]$Name,
        [string]$Method,
        [string]$Path,
        $Body = $null,
        [hashtable]$Form = $null,
        [string]$Token = $null,
        [int[]]$Expect = @(200, 201, 204),
        [string]$Base = $gateway,
        [switch]$Redact,
        [string]$Note = ''
    )
    $url = if ($Path -match '^https?://') { $Path } else { $Base + $Path }
    $req = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), [uri]$url)
    if ($Token) {
        $req.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $Token)
    }
    $reqText = ''
    if ($Form) {
        $mp = [System.Net.Http.MultipartFormDataContent]::new()
        foreach ($k in $Form.Keys) {
            $part = [System.Net.Http.StringContent]::new([string]$Form[$k], [System.Text.Encoding]::UTF8)
            $null = $part.Headers.Remove('Content-Type')
            $mp.Add($part, $k)
        }
        $req.Content = $mp
        $reqText = ($Form.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" } | Sort-Object) -join '& '
    }
    elseif ($null -ne $Body) {
        $json = if ($Body -is [string]) { $Body } else { $Body | ConvertTo-Json -Depth 20 -Compress }
        $req.Content = [System.Net.Http.StringContent]::new($json, [System.Text.Encoding]::UTF8, 'application/json')
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
    try {
        $resp = $script:Http.SendAsync($req).GetAwaiter().GetResult()
        $bytes = $resp.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
        $text = [System.Text.Encoding]::UTF8.GetString($bytes)
        $status = [int]$resp.StatusCode
        if ($resp.Content.Headers.ContentType) { $ctype = $resp.Content.Headers.ContentType.ToString() }
        $resp.Dispose()
    }
    catch {
        $err = $_.Exception.GetBaseException().Message
        $text = "TRANSPORT ERROR: $err"
    }
    $sw.Stop()

    $ok = $Expect -contains $status
    $json = $null
    if ($text -and $text.TrimStart().StartsWith('{') -or $text.TrimStart().StartsWith('[')) {
        try { $json = $text | ConvertFrom-Json } catch { $json = $null }
    }
    $shortText = if ($text.Length -gt 5000) { $text.Substring(0, 5000) + '…[truncated]' } else { $text }
    $rec = [pscustomobject]@{
        Group    = $Group
        Name     = $Name
        Method   = $Method
        Path     = $Path
        Status   = $status
        Expected = ($Expect -join '/')
        Ok       = $ok
        ElapsedMs= [int]$sw.ElapsedMilliseconds
        Request  = $reqText
        Response = $shortText
        Note     = $Note
        Time     = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
        Json     = $json
    }
    if (-not $script:ResultsByGroup.ContainsKey($Group)) { $script:ResultsByGroup[$Group] = [System.Collections.Generic.List[object]]::new() }
    $script:ResultsByGroup[$Group].Add($rec)

    $tag = if ($ok) { 'PASS' } else { 'FAIL' }
    Write-Host ("[{0}] {1,-42} {2,-6} {3,3} {4,5}ms  {5}" -f $tag, $Name, $Method, $status, $rec.ElapsedMs, $Path)
    if (-not $ok) {
        Write-Host ("        expected {0}; body: {1}" -f $rec.Expected, ($shortText -replace "`r?`n", ' ').Substring(0, [Math]::Min(400, $shortText.Length)))
        if ($StopOnFail) { throw "Test failed: $Name ($status)" }
    }
    return $rec
}

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "ASSERTION FAILED: $Message" }
}

function Login {
    param([string]$Email, [string]$Password, [string]$Label)
    $r = Invoke-Api -Group 'auth' -Name "Đăng nhập $Label" -Method POST -Path '/auth/users/login' -Body @{ email = $Email; password = $Password } -Expect 200 -Redact
    if ($r.Ok) { return $r.Json.result }
    return $null
}

function New-Uuid { [guid]::NewGuid().ToString() }

# ---------------- auth group ----------------
function Run-Auth {
    $admin = Login 'admin@example.com' 'admin123' 'admin'
    Assert-True ($null -ne $admin) 'admin login failed'
    $script:State['adminToken'] = $admin.token
    $script:State['adminRefresh'] = $admin.refreshToken

    $u1 = Login 'user1@gmail.com' 'user12345' 'user1 (account 3)'
    Assert-True ($null -ne $u1) 'user1 login failed'
    $script:State['user1Token'] = $u1.token

    $u5 = Login 'user5@gmail.com' 'user12345' 'user5 (account 7)'
    Assert-True ($null -ne $u5) 'user5 login failed'
    $script:State['user5Token'] = $u5.token

    # introspect is internal-only through the gateway, so call the service directly
    $intro = Invoke-Api -Group 'auth' -Name 'Kiểm tra token (introspect nội bộ)' -Method POST -Path 'http://localhost:8080/auth/users/introspect' -Body @{ token = $script:State['adminToken'] } -Expect 200 -Redact
    Assert-True ($intro.Ok -and $intro.Json.result.valid) 'introspect should be valid'

    $refresh = Invoke-Api -Group 'auth' -Name 'Làm mới token admin' -Method POST -Path '/auth/users/refresh' -Body @{ token = $script:State['adminRefresh'] } -Expect 200 -Redact
    if ($refresh.Ok) {
        $script:State['adminToken'] = $refresh.Json.result.token
        $script:State['adminRefresh'] = $refresh.Json.result.refreshToken
    }

    # public registration
    $stamp = Get-Date -Format 'HHmmssfff'
    $regEmail = "sgu.reg.$stamp@sgu-test.local"
    $regUser = "sgureg$stamp"
    $reg = Invoke-Api -Group 'auth' -Name 'Đăng ký khách hàng công khai' -Method POST -Path '/auth/users/register' -Body @{
        username = $regUser; email = $regEmail; phone = '0901234567'; address = '1 Nguyễn Huệ, TP.HCM'; password = 'SguTest12345'
    } -Expect 200 -Redact
    Assert-True $reg.Ok 'register failed'
    $regId = [string]$reg.Json.result.id
    $script:State['regUserId'] = $regId

    $regLogin = Invoke-Api -Group 'auth' -Name 'Đăng nhập tài khoản vừa đăng ký' -Method POST -Path '/auth/users/login' -Body @{ email = $regEmail; password = 'SguTest12345' } -Expect 200 -Redact
    Assert-True $regLogin.Ok 'registered account login failed'
    $regToken = $regLogin.Json.result.token

    $changepw = Invoke-Api -Group 'auth' -Name 'Đổi mật khẩu tài khoản vừa đăng ký' -Method POST -Path '/auth/users/Change-password' -Token $regToken -Body @{ oldPassword = 'SguTest12345'; newPassword = 'SguTest67890'; confirmPassword = 'SguTest67890' } -Expect 200 -Redact
    Assert-True $changepw.Ok 'change password failed'

    $regLogin2 = Invoke-Api -Group 'auth' -Name 'Đăng nhập bằng mật khẩu mới' -Method POST -Path '/auth/users/login' -Body @{ email = $regEmail; password = 'SguTest67890' } -Expect 200 -Redact
    Assert-True $regLogin2.Ok 'login with new password failed'
    $regToken2 = $regLogin2.Json.result.token

    $self = Invoke-Api -Group 'auth' -Name 'Xem hồ sơ của chính mình' -Method GET -Path "/auth/users/$regId" -Token $regToken2 -Expect 200
    Assert-True ($self.Ok -and [string]$self.Json.result.id -eq $regId) 'self profile mismatch'

    $list = Invoke-Api -Group 'auth' -Name 'Danh sách tài khoản (admin)' -Method GET -Path '/auth/users/list?page=0&size=5' -Token $script:State['adminToken'] -Expect 200

    $names = Invoke-Api -Group 'auth' -Name 'Lấy tên hiển thị theo id' -Method GET -Path '/auth/users/display-names?ids=1,3,7' -Token $script:State['adminToken'] -Expect 200
    Assert-True ($names.Ok -and $null -ne $names.Json.result.'1') 'display names missing id 1'

    # admin creates a managed staff/customer account for the archive flow
    $create = Invoke-Api -Group 'auth' -Name 'Admin tạo tài khoản quản lý' -Method POST -Path '/auth/users' -Token $script:State['adminToken'] -Body @{
        username = "sguadm$stamp"; email = "sgu.adm.$stamp@sgu-test.local"; phone = '0912345678'; address = '2 Lê Lợi, TP.HCM'; password = 'SguAdmin12345'; roleId = 2
    } -Expect 200 -Redact
    Assert-True $create.Ok 'admin create user failed'
    $managedId = [string]$create.Json.result.id
    $script:State['managedUserId'] = $managedId

    $upd = Invoke-Api -Group 'auth' -Name 'Cập nhật hồ sơ tài khoản quản lý' -Method POST -Path "/auth/users/UpdateUser/$managedId" -Token $script:State['adminToken'] -Form @{
        username = "sguadm$stamp"; phone = '0987654321'; address = '99 Trần Hưng Đạo, TP.HCM'
    } -Expect 200
    Assert-True $upd.Ok 'update user failed'

    $getManaged = Invoke-Api -Group 'auth' -Name 'Xem chi tiết tài khoản quản lý' -Method GET -Path "/auth/users/$managedId" -Token $script:State['adminToken'] -Expect 200

    # lock + delete a throwaway account (no history)
    $lockReg = Invoke-Api -Group 'auth' -Name 'Khóa tài khoản đăng ký mới' -Method PUT -Path "/auth/users/$regId/status" -Token $script:State['adminToken'] -Body @{ status = 'LOCKED'; reason = 'Kiểm thử luồng vòng đời tài khoản' } -Expect 200
    Assert-True ($lockReg.Ok -and $lockReg.Json.result.status -eq 'LOCKED') 'lock failed'
    $delReg = Invoke-Api -Group 'auth' -Name 'Xóa tài khoản đã khóa (không có lịch sử)' -Method DELETE -Path "/auth/users/${regId}?reason=Kiem-thu-xoa-tai-khoan" -Token $script:State['adminToken'] -Expect 200
    Assert-True $delReg.Ok 'delete failed'

    # lock + archive the managed account, then read its event history
    $lockManaged = Invoke-Api -Group 'auth' -Name 'Khóa tài khoản quản lý' -Method PUT -Path "/auth/users/$managedId/status" -Token $script:State['adminToken'] -Body @{ status = 'LOCKED'; reason = 'Kiểm thử lưu trữ tài khoản' } -Expect 200
    Assert-True $lockManaged.Ok 'lock managed failed'
    $arch = Invoke-Api -Group 'auth' -Name 'Lưu trữ tài khoản đã khóa' -Method POST -Path "/auth/users/$managedId/archive" -Token $script:State['adminToken'] -Body @{ reason = 'Kiểm thử lưu trữ tài khoản' } -Expect 200
    Assert-True ($arch.Ok -and $arch.Json.result.status -eq 'ARCHIVED') 'archive failed'
    $events = Invoke-Api -Group 'auth' -Name 'Xem lịch sử trạng thái tài khoản' -Method GET -Path "/auth/users/$managedId/events" -Token $script:State['adminToken'] -Expect 200
    Assert-True ($events.Ok -and @($events.Json.result).Count -ge 2) 'events missing'

    # logout (revokes the current session), then log in again for later groups
    $logout = Invoke-Api -Group 'auth' -Name 'Đăng xuất admin' -Method POST -Path '/auth/users/logout' -Body @{ token = $script:State['adminToken']; refreshToken = $script:State['adminRefresh'] } -Expect 200 -Redact
    Assert-True $logout.Ok 'logout failed'
    $admin2 = Login 'admin@example.com' 'admin123' 'admin (sau đăng xuất)'
    Assert-True ($null -ne $admin2) 'admin re-login failed'
    $script:State['adminToken'] = $admin2.token
    $script:State['adminRefresh'] = $admin2.refreshToken

    # ---------- roles ----------
    $roleName = "SGU Test Role $stamp"
    $role = Invoke-Api -Group 'auth' -Name 'Tạo vai trò' -Method POST -Path '/auth/roles' -Token $script:State['adminToken'] -Body @{
        name = $roleName; description = 'Vai trò kiểm thử API'; active = $true; permissions = @()
    } -Expect 200
    Assert-True $role.Ok 'create role failed'
    $roleId = [string]$role.Json.result.id
    $script:State['roleId'] = $roleId

    $roleUpd = Invoke-Api -Group 'auth' -Name 'Cập nhật vai trò' -Method PUT -Path '/auth/roles' -Token $script:State['adminToken'] -Body @{
        id = [int]$roleId; name = $roleName; description = 'Vai trò kiểm thử API (đã cập nhật)'; active = $true; permissions = @()
    } -Expect 200
    Assert-True $roleUpd.Ok 'update role failed'

    $roleList = Invoke-Api -Group 'auth' -Name 'Danh sách vai trò' -Method GET -Path '/auth/roles' -Token $script:State['adminToken'] -Expect 200
    $roleGet = Invoke-Api -Group 'auth' -Name 'Xem chi tiết vai trò' -Method GET -Path "/auth/roles/$roleId" -Token $script:State['adminToken'] -Expect 200

    # ---------- permissions ----------
    $perm = Invoke-Api -Group 'auth' -Name 'Tạo quyền' -Method POST -Path '/auth/permissions' -Token $script:State['adminToken'] -Body @{
        code = "SGU_TEST_$stamp"; name = "SGU Test Permission $stamp"; apiPath = "/sgu-test/$stamp"; method = 'GET'; module = 'TEST'; roleIds = @()
    } -Expect 200
    Assert-True $perm.Ok 'create permission failed'
    $permId = [string]$perm.Json.result.id
    $script:State['permId'] = $permId

    $permUpd = Invoke-Api -Group 'auth' -Name 'Cập nhật quyền' -Method PUT -Path '/auth/permissions' -Token $script:State['adminToken'] -Body @{
        id = [int]$permId; code = "SGU_TEST_$stamp"; name = "SGU Test Permission $stamp (updated)"; apiPath = "/sgu-test/$stamp"; method = 'GET'; module = 'TEST'; roleIds = @()
    } -Expect 200
    Assert-True $permUpd.Ok 'update permission failed'
    $permList = Invoke-Api -Group 'auth' -Name 'Danh sách quyền' -Method GET -Path '/auth/permissions/list' -Token $script:State['adminToken'] -Expect 200
    $permGet = Invoke-Api -Group 'auth' -Name 'Xem chi tiết quyền' -Method GET -Path "/auth/permissions/$permId" -Token $script:State['adminToken'] -Expect 200
    $permDel = Invoke-Api -Group 'auth' -Name 'Xóa quyền' -Method DELETE -Path "/auth/permissions/$permId" -Token $script:State['adminToken'] -Expect 200
    Assert-True $permDel.Ok 'delete permission failed'
    $roleDel = Invoke-Api -Group 'auth' -Name 'Xóa vai trò' -Method DELETE -Path "/auth/roles/$roleId" -Token $script:State['adminToken'] -Expect 200
    Assert-True $roleDel.Ok 'delete role failed'

    # ---------- reviews ----------
    $elig = Invoke-Api -Group 'auth' -Name 'Kiểm tra điều kiện đánh giá tour 15 (user5)' -Method GET -Path '/auth/reviews/eligibility?tourId=15' -Token $script:State['user5Token'] -Expect 200
    Assert-True ($elig.Ok -and $elig.Json.result.eligible -eq $true) 'user5 should be eligible after removing seed review'

    $review = Invoke-Api -Group 'auth' -Name 'Gửi đánh giá tour 15 (user5)' -Method POST -Path '/auth/reviews' -Token $script:State['user5Token'] -Body @{
        tourId = 15; content = 'Chuyến đi kiểm thử: hướng dẫn viên chu đáo, lịch trình rõ ràng và đúng giờ.'; rating = 5
    } -Expect 200
    Assert-True $review.Ok 'create review failed'

    $byTour = Invoke-Api -Group 'auth' -Name 'Đánh giá theo tour (công khai)' -Method GET -Path '/auth/reviews/tour/15?page=0&size=10' -Expect 200
    Assert-True ($byTour.Ok -and @($byTour.Json.result).Count -ge 1) 'review by tour empty'
    $high = Invoke-Api -Group 'auth' -Name 'Đánh giá điểm cao (công khai)' -Method GET -Path '/auth/reviews/high-rating?size=4' -Expect 200
    $revList = Invoke-Api -Group 'auth' -Name 'Danh sách đánh giá (admin)' -Method GET -Path '/auth/reviews/list?page=0&size=5' -Token $script:State['adminToken'] -Expect 200

    Save-State
}

# ---------------- tour / pricing group ----------------
function Run-Tour {
    $admin = $script:State['adminToken']
    if (-not $admin) { throw 'admin token missing; run auth group first' }

    $list = Invoke-Api -Group 'tour' -Name 'Danh sách tour' -Method GET -Path '/tour/tours/list' -Expect 200
    $detail = Invoke-Api -Group 'tour' -Name 'Chi tiết tour 1' -Method GET -Path '/tour/tours/1' -Expect 200
    $random = Invoke-Api -Group 'tour' -Name 'Ba tour ngẫu nhiên' -Method GET -Path '/tour/tours/random-3' -Expect 200
    $search = Invoke-Api -Group 'tour' -Name 'Tìm tour theo từ khóa' -Method GET -Path '/tour/tours/search?title=Hà&page=0&size=5' -Expect 200
    $snapshot = Invoke-Api -Group 'tour' -Name 'Snapshot danh mục cho search' -Method GET -Path '/tour/tours/search-snapshot' -Token $admin -Expect 200
    $advLoc = Invoke-Api -Group 'tour' -Name 'Danh mục địa điểm tư vấn' -Method GET -Path '/tour/advice/locations' -Expect 200
    $advSearch = Invoke-Api -Group 'tour' -Name 'Tư vấn tìm tour (công khai)' -Method POST -Path '/tour/advice/search' -Body @{
        criteria = @{ region = 'CENTRAL'; searchMode = 'BOOKABLE' }; page = 0; size = 5
    } -Expect 200
    Assert-True ($advSearch.Ok -and $null -ne $advSearch.Json.result) 'advice search failed'

    # vehicles
    $vehId = "SGU-BUS-$((Get-Date).ToString('HHmmss'))"
    $veh = Invoke-Api -Group 'tour' -Name 'Tạo phương tiện' -Method POST -Path '/tour/vehicles' -Token $admin -Body @{ id = $vehId; vehicleType = 'Xe kiểm thử 45 chỗ' } -Expect 200
    Assert-True $veh.Ok 'create vehicle failed'
    $vehList = Invoke-Api -Group 'tour' -Name 'Danh sách phương tiện' -Method GET -Path '/tour/vehicles/list' -Expect 200

    # pricing
    $price = Invoke-Api -Group 'tour' -Name 'Tạo bảng giá tour' -Method POST -Path '/pricing/prices' -Token $admin -Body @{
        adultPrice = 1250000; childPrice = 900000; toddlerPrice = 0; infantPrice = 150000; singleSupplementPrice = 400000
    } -Expect 200
    Assert-True $price.Ok 'create price failed'
    $priceId = [string]$price.Json.result.id
    $script:State['priceId'] = $priceId
    $priceUpd = Invoke-Api -Group 'tour' -Name 'Cập nhật bảng giá tour' -Method PUT -Path "/pricing/prices/$priceId" -Token $admin -Body @{
        adultPrice = 1290000; childPrice = 950000; toddlerPrice = 0; infantPrice = 160000; singleSupplementPrice = 420000
    } -Expect 200
    Assert-True $priceUpd.Ok 'update price failed'
    $priceGet = Invoke-Api -Group 'tour' -Name 'Chi tiết bảng giá (công khai)' -Method GET -Path "/pricing/prices/$priceId" -Expect 200
    $priceAll = Invoke-Api -Group 'tour' -Name 'Danh sách bảng giá' -Method GET -Path '/pricing/prices' -Token $admin -Expect 200
    $priceBatch = Invoke-Api -Group 'tour' -Name 'Lấy giá theo lô (công khai)' -Method POST -Path '/pricing/prices/batch' -Body @{ ids = @(1, [int]$priceId) } -Expect 200
    Assert-True ($priceBatch.Ok -and @($priceBatch.Json.result).Count -eq 2) 'price batch failed'

    # locations
    $city = "SGU Test City $((Get-Date).ToString('HHmmss'))"
    $loc = Invoke-Api -Group 'tour' -Name 'Tạo địa điểm (multipart)' -Method POST -Path '/tour/locations' -Token $admin -Form @{ city = $city; type = 'DESTINATION' } -Expect 200
    Assert-True $loc.Ok 'create location failed'
    $locId = [string]$loc.Json.result.id
    $script:State['locationId'] = $locId
    $locUpd = Invoke-Api -Group 'tour' -Name 'Cập nhật địa điểm' -Method PUT -Path "/tour/locations/$locId" -Token $admin -Form @{ city = "$city (updated)"; type = 'DESTINATION' } -Expect 200
    Assert-True $locUpd.Ok 'update location failed'
    $locAll = Invoke-Api -Group 'tour' -Name 'Tất cả địa điểm' -Method GET -Path '/tour/locations/all' -Expect 200
    $depLoc = Invoke-Api -Group 'tour' -Name 'Địa điểm khởi hành' -Method GET -Path '/tour/locations/departures' -Expect 200
    $desLoc = Invoke-Api -Group 'tour' -Name 'Địa điểm đến' -Method GET -Path '/tour/locations/destinations' -Expect 200
    $depSearch = Invoke-Api -Group 'tour' -Name 'Tìm địa điểm khởi hành' -Method GET -Path ([uri]::EscapeUriString('/tour/locations/departures/search?keyword=Hà Nội')) -Expect 200
    $desSearch = Invoke-Api -Group 'tour' -Name 'Tìm địa điểm đến' -Method GET -Path '/tour/locations/destinations/search?keyword=Sa' -Expect 200
    $attractive = Invoke-Api -Group 'tour' -Name 'Địa điểm hấp dẫn' -Method GET -Path '/tour/locations/destinations/attractive' -Expect 200

    # tour + itinerary lifecycle
    $tourTitle = "Tour kiểm thử API $((Get-Date).ToString('HHmmss'))"
    $tour = Invoke-Api -Group 'tour' -Name 'Tạo tour (multipart, không ảnh)' -Method POST -Path '/tour/tours' -Token $admin -Form @{
        tourProgram = 'SGU-TEST'; tourTitle = $tourTitle; description = 'Tour tạo tự động bởi bộ kiểm thử API'; duration = '4';
        departureLocationId = '1'; destinationLocationId = $locId; basePrice = '1290000'; vehicleId = $vehId; tourPriceId = $priceId
    } -Expect 200
    Assert-True $tour.Ok 'create tour failed'
    $tourId = [string]$tour.Json.result.id
    $script:State['tourId'] = $tourId

    $tourUpd = Invoke-Api -Group 'tour' -Name 'Cập nhật tour' -Method PUT -Path "/tour/tours/$tourId" -Token $admin -Form @{
        tourProgram = 'SGU-TEST'; tourTitle = "$tourTitle (updated)"; description = 'Tour đã được cập nhật bởi bộ kiểm thử API'; duration = '4';
        departureLocationId = '1'; destinationLocationId = $locId; basePrice = '1290000'; vehicleId = $vehId; tourPriceId = $priceId
    } -Expect 200
    Assert-True $tourUpd.Ok 'update tour failed'
    $tourGet = Invoke-Api -Group 'tour' -Name 'Chi tiết tour vừa tạo' -Method GET -Path "/tour/tours/$tourId" -Expect 200

    $itin = Invoke-Api -Group 'tour' -Name 'Tạo lịch trình' -Method POST -Path '/tour/itineraries' -Token $admin -Body @{
        title = 'Ngày 1: Khởi hành'; description = 'Tập trung tại điểm hẹn và khởi hành'; meal = 'Trưa, tối'; tourId = [int]$tourId
    } -Expect 200
    Assert-True $itin.Ok 'create itinerary failed'
    $itinId = [string]$itin.Json.result.id
    $itinUpd = Invoke-Api -Group 'tour' -Name 'Cập nhật lịch trình' -Method PUT -Path "/tour/itineraries/$itinId" -Token $admin -Body @{
        title = 'Ngày 1: Khởi hành (updated)'; description = 'Tập trung sớm và khởi hành đúng giờ'; meal = 'Sáng, trưa, tối'; tourId = [int]$tourId
    } -Expect 200
    Assert-True $itinUpd.Ok 'update itinerary failed'
    $itinGet = Invoke-Api -Group 'tour' -Name 'Chi tiết lịch trình' -Method GET -Path "/tour/itineraries/$itinId" -Token $admin -Expect 200
    $itinByTour = Invoke-Api -Group 'tour' -Name 'Lịch trình theo tour (công khai)' -Method GET -Path "/tour/itineraries/tour/$tourId" -Expect 200
    $itinAll = Invoke-Api -Group 'tour' -Name 'Tất cả lịch trình' -Method GET -Path '/tour/itineraries/all' -Token $admin -Expect 200
    $itinDel = Invoke-Api -Group 'tour' -Name 'Xóa lịch trình' -Method DELETE -Path "/tour/itineraries/$itinId" -Token $admin -Expect 200
    Assert-True $itinDel.Ok 'delete itinerary failed'

    $tourDel = Invoke-Api -Group 'tour' -Name 'Xóa tour vừa tạo' -Method DELETE -Path "/tour/tours/$tourId" -Token $admin -Expect 200
    Assert-True $tourDel.Ok 'delete tour failed'

    # departures on an existing tour (avoid leaving a new tour with history).
    # Minute-level unique date keeps repeated runs from colliding on the same departure_date.
    $depDate = (Get-Date).Date.AddDays(500).AddHours((Get-Date).Hour).AddMinutes((Get-Date).Minute)
    $depReturn = $depDate.AddDays(3).AddHours(13).AddMinutes(30)
    $depDateText = $depDate.ToString('yyyy-MM-ddTHH:mm:ss')
    $depReturnText = $depReturn.ToString('yyyy-MM-ddTHH:mm:ss')
    $dep = Invoke-Api -Group 'tour' -Name 'Tạo lịch khởi hành mới' -Method POST -Path '/tour/tour-departures' -Token $admin -Body @{
        departureDate = $depDateText; returnDate = $depReturnText; capacity = 36; tourId = 1
    } -Expect 200
    Assert-True $dep.Ok 'create departure failed'
    $depId = [string]$dep.Json.result.id
    $depVer = [int]$dep.Json.result.version
    $script:State['departureId'] = $depId
    $depUpd = Invoke-Api -Group 'tour' -Name 'Cập nhật sức chứa lịch khởi hành' -Method PUT -Path "/tour/tour-departures/$depId" -Token $admin -Body @{
        departureDate = $depDateText; returnDate = $depReturnText; capacity = 40; expectedVersion = $depVer; confirmCapacity = $false; tourId = 1
    } -Expect 200
    Assert-True $depUpd.Ok 'update departure failed'
    $depGet = Invoke-Api -Group 'tour' -Name 'Chi tiết lịch khởi hành (công khai)' -Method GET -Path "/tour/tour-departures/$depId" -Expect 200
    $depCap = Invoke-Api -Group 'tour' -Name 'Tổng quan sức chứa lịch khởi hành' -Method GET -Path '/tour/tour-departures/313/capacity' -Token $admin -Expect 200
    $depList = Invoke-Api -Group 'tour' -Name 'Danh sách lịch khởi hành' -Method GET -Path '/tour/tour-departures/list' -Token $admin -Expect 200
    $depByTour = Invoke-Api -Group 'tour' -Name 'Lịch khởi hành theo tour (công khai)' -Method GET -Path '/tour/tour-departures/tour/1' -Expect 200
    $depHist = Invoke-Api -Group 'tour' -Name 'Toàn bộ lịch sử lịch khởi hành tour 1' -Method GET -Path '/tour/tour-departures/tour/1/all' -Token $admin -Expect 200
    Save-State
}

# ---------------- booking / payment / invoice / search / file / ai ----------------
function Run-Booking {
    $admin = $script:State['adminToken']
    $u1 = $script:State['user1Token']
    $u5 = $script:State['user5Token']
    if (-not $admin -or -not $u1 -or -not $u5) { throw 'tokens missing; run auth group first' }

    # fresh bookings
    $bkReq1 = @{
        userId = '3'; tourDepartureId = '611'; clientKey = (New-Uuid)
        listOfCustomers = @(@{ fullName = 'Nguyễn Văn Kiểm Thử'; birthdate = '1990-05-20'; address = '1 Nguyễn Huệ, TP.HCM'; gender = 'Male'; status = 'BOOKED' })
    }
    $bk1 = Invoke-Api -Group 'booking' -Name 'Tạo booking (giữ chỗ) - lịch 611' -Method POST -Path '/booking/book/create' -Token $u1 -Body $bkReq1 -Expect 200
    Assert-True $bk1.Ok 'create booking 1 failed'
    $bk1Id = [string]$bk1.Json.result.id
    $script:State['booking1Id'] = $bk1Id
    Assert-True ($bk1.Json.result.status -eq 'PENDING_PAYMENT') 'new booking should be PENDING_PAYMENT'

    $bk2 = Invoke-Api -Group 'booking' -Name 'Tạo booking thứ hai - lịch 626' -Method POST -Path '/booking/book/create' -Token $u1 -Body @{
        userId = '3'; tourDepartureId = '626'; clientKey = (New-Uuid)
        listOfCustomers = @(@{ fullName = 'Trần Thị Hủy'; birthdate = '1992-08-10'; address = '2 Lê Lợi, TP.HCM'; gender = 'Female'; status = 'BOOKED' })
    } -Expect 200
    Assert-True $bk2.Ok 'create booking 2 failed'
    $bk2Id = [string]$bk2.Json.result.id

    $bkGet = Invoke-Api -Group 'booking' -Name 'Xem booking của chính mình' -Method GET -Path "/booking/book/$bk1Id" -Token $u1 -Expect 200
    $bkAll = Invoke-Api -Group 'booking' -Name 'Danh sách booking (admin)' -Method GET -Path '/booking/book/all?page=0&size=5' -Token $admin -Expect 200
    $bkMine = Invoke-Api -Group 'booking' -Name 'Danh sách booking của tôi' -Method GET -Path '/booking/book/all?page=0&size=10&mine=true' -Token $u1 -Expect 200
    $bkCancel = Invoke-Api -Group 'booking' -Name 'Hủy booking thứ hai' -Method DELETE -Path "/booking/book/$bk2Id" -Token $u1 -Expect 200
    Assert-True ($bkCancel.Ok -and $bkCancel.Json.result.status -eq 'CANCELLED') 'cancel booking failed'

    # customers
    $custBooking = Invoke-Api -Group 'booking' -Name 'Danh sách hành khách theo booking 22' -Method GET -Path '/booking/customer/booking/22' -Token $u1 -Expect 200
    Assert-True ($custBooking.Ok -and @($custBooking.Json.result).Count -ge 1) 'customers by booking empty'
    $custId = [string]@($custBooking.Json.result)[0].id
    $custGet = Invoke-Api -Group 'booking' -Name 'Chi tiết hành khách' -Method GET -Path "/booking/customer/$custId" -Token $u1 -Expect 200
    $custMine = Invoke-Api -Group 'booking' -Name 'Hành khách của tôi' -Method GET -Path '/booking/customer/all?page=0&size=10&mine=true' -Token $u1 -Expect 200
    $custAll = Invoke-Api -Group 'booking' -Name 'Tất cả hành khách (admin)' -Method GET -Path '/booking/customer/all?page=0&size=5' -Token $admin -Expect 200

    # advice
    $adv = Invoke-Api -Group 'booking' -Name 'Tình trạng booking của tôi (advice)' -Method GET -Path '/booking/advice/mine?bookingId=22' -Token $u1 -Expect 200
    $elig1 = Invoke-Api -Group 'booking' -Name 'Điều kiện đánh giá tour 1 (user1)' -Method GET -Path '/booking/advice/review-eligibility?tourId=1' -Token $u1 -Expect 200

    # change: eligibility / options / customer request / admin review
    $chElig = Invoke-Api -Group 'booking' -Name 'Booking đủ điều kiện đổi lịch' -Method GET -Path '/booking/changes/eligible?page=0&size=10' -Token $u1 -Expect 200
    $chMine = Invoke-Api -Group 'booking' -Name 'Yêu cầu đổi lịch của tôi' -Method GET -Path '/booking/changes/mine?page=0&size=10' -Token $u1 -Expect 200
    $chOpt = Invoke-Api -Group 'booking' -Name 'Các lịch thay thế cho booking 22' -Method GET -Path '/booking/changes/options?bookingId=22' -Token $u1 -Expect 200
    Assert-True ($chOpt.Ok -and @($chOpt.Json.result).Count -ge 1) 'no change options'

    $clientKey = New-Uuid
    $chCreate = Invoke-Api -Group 'booking' -Name 'Yêu cầu đổi lịch (khách, booking 22 → 566)' -Method POST -Path '/booking/changes/customer' -Token $u1 -Body @{
        clientKey = $clientKey; bookingId = 22; targetId = 566; reason = 'Gia đình có việc riêng, xin đổi sang lịch khác cùng thời lượng'
    } -Expect 200
    Assert-True $chCreate.Ok 'customer change failed'
    $chId = [string]$chCreate.Json.result.id
    $chVer = [int]$chCreate.Json.result.version
    $script:State['changeId'] = $chId

    # replaying the same clientKey must be idempotent
    $chReplay = Invoke-Api -Group 'booking' -Name 'Gửi lại cùng clientKey (idempotent)' -Method POST -Path '/booking/changes/customer' -Token $u1 -Body @{
        clientKey = $clientKey; bookingId = 22; targetId = 566; reason = 'Gia đình có việc riêng, xin đổi sang lịch khác cùng thời lượng'
    } -Expect 200 -Note 'Yêu cầu trùng clientKey trả lại bản ghi cũ'

    $chAdmin = Invoke-Api -Group 'booking' -Name 'Danh sách yêu cầu đổi lịch (admin)' -Method GET -Path '/booking/changes/admin?tourId=1&page=0&size=10' -Token $admin -Expect 200
    $chReview = Invoke-Api -Group 'booking' -Name 'Admin duyệt yêu cầu đổi lịch' -Method POST -Path "/booking/changes/$chId/review" -Token $admin -Body @{
        action = 'APPROVE'; expectedVersion = $chVer; note = 'Đã kiểm tra và chấp thuận đổi lịch'
    } -Expect 200
    Assert-True ($chReview.Ok -and $chReview.Json.result.status -eq 'APPROVED') 'approve change failed'
    $chMine2 = Invoke-Api -Group 'booking' -Name 'Khách xem yêu cầu đã được duyệt' -Method GET -Path '/booking/changes/mine?page=0&size=10' -Token $u1 -Expect 200

    # shop incident workflow on departure 615 (booking 26, user5)
    $dep615 = Invoke-Api -Group 'booking' -Name 'Đọc lịch 615 trước khi xử lý sự cố' -Method GET -Path '/tour/tour-departures/615' -Expect 200
    $dep615Ver = [int]$dep615.Json.result.version

    $shopBase = @{
        sourceId = 615; expectedVersion = $dep615Ver; reasonCode = 'NATURAL_DISASTER'
        reason = 'Bão lớn làm tuyến đường và bến tàu không thể vận hành an toàn'
        evidence = 'Công văn số 123/UBND về việc cấm ra khơi'
        originalCannotDepart = $true
        departureAt = '2026-12-15T06:00:00'; returnAt = '2026-12-17T19:30:00'
        capacity = 40; responseDeadline = '2026-11-20T17:00:00'
    }
    $shopPreviewBody = $shopBase.Clone(); $shopPreviewBody['clientKey'] = New-Uuid
    $preview = Invoke-Api -Group 'booking' -Name 'Xem trước phương án thay thế sự cố' -Method POST -Path '/booking/changes/shop/preview' -Token $admin -Body $shopPreviewBody -Expect 200
    Assert-True ($preview.Ok -and [int]$preview.Json.result.bookingCount -ge 1) 'shop preview failed'

    $shopKey = New-Uuid
    $shopBody = $shopBase.Clone(); $shopBody['clientKey'] = $shopKey
    $shop = Invoke-Api -Group 'booking' -Name 'Tạo phương án thay thế do sự cố' -Method POST -Path '/booking/changes/shop' -Token $admin -Body $shopBody -Expect 200
    Assert-True $shop.Ok 'shop create failed'
    $rootId = [string]$shop.Json.result.id
    $script:State['shopRootId'] = $rootId

    $u5Changes = Invoke-Api -Group 'booking' -Name 'User5 xem đề xuất đổi lịch' -Method GET -Path '/booking/changes/mine?page=0&size=20' -Token $u5 -Expect 200
    $shopChange = @($u5Changes.Json.result) | Where-Object { [string]$_.booking_id -eq '26' } | Select-Object -First 1
    Assert-True ($null -ne $shopChange) 'shop change for booking 26 not found'
    $shopChangeId = [string]$shopChange.id

    $record = Invoke-Api -Group 'booking' -Name 'Admin ghi nhận yêu cầu hoàn tiền' -Method POST -Path "/booking/changes/$shopChangeId/review" -Token $admin -Body @{
        action = 'RECORD_REFUND_REQUEST'; expectedVersion = [int]$shopChange.version; note = 'Khách yêu cầu hoàn tiền toàn bộ qua điện thoại'
    } -Expect 200
    Assert-True $record.Ok 'record refund request failed'

    $respond = Invoke-Api -Group 'booking' -Name 'Khách chọn phương án hoàn tiền' -Method POST -Path "/booking/changes/$shopChangeId/respond" -Token $u5 -Body @{
        action = 'REFUND'; expectedVersion = [int]$record.Json.result.version; note = 'Tôi chọn hoàn tiền toàn bộ'
    } -Expect 200
    Assert-True $respond.Ok 'customer respond refund failed'

    $refund = Invoke-Api -Group 'booking' -Name 'Admin ghi nhận đã hoàn tiền' -Method POST -Path "/booking/changes/$shopChangeId/refund" -Token $admin -Body @{
        expectedVersion = [int]$respond.Json.result.version; externalRefundCompleted = $true; amount = 4990000
        reference = "SGU-REFUND-$((Get-Date).ToString('HHmmss'))"; note = 'Đã chuyển khoản hoàn tiền toàn bộ cho khách'
    } -Expect 200
    Assert-True ($refund.Ok -and $refund.Json.result.status -eq 'REFUNDED') 'refund failed'

    $activate = Invoke-Api -Group 'booking' -Name 'Kích hoạt lịch thay thế sau khi xử lý xong' -Method POST -Path "/booking/changes/shop/$rootId/activate" -Token $admin -Expect 200
    Assert-True ($activate.Ok -and $activate.Json.result.status -eq 'COMPLETED') 'activate replacement failed'

    # VNPay
    $quote = Invoke-Api -Group 'booking' -Name 'Báo giá thanh toán VNPay' -Method GET -Path "/payment/vnpay/bookings/$bk1Id/quote" -Token $u1 -Expect 200
    Assert-True $quote.Ok 'vnpay quote failed'
    $amount = [string]$quote.Json.result.amount
    Assert-True ($amount -eq '8490000') "unexpected amount $amount"

    $createPay = Invoke-Api -Group 'booking' -Name 'Tạo URL thanh toán VNPay' -Method POST -Path '/payment/vnpay/bookings' -Token $u1 -Body @{ bookingId = [int]$bk1Id; expectedAmount = $amount } -Expect 200
    Assert-True $createPay.Ok 'create vnpay payment failed'
    $payUrl = $createPay.Response.Trim()
    Assert-True ($payUrl -like 'http*') 'payment URL not returned'
    $query = ([uri]$payUrl).Query.TrimStart('?')
    $fields = @{}
    foreach ($pair in $query.Split('&')) {
        $kv = $pair.Split('=', 2)
        if ($kv.Count -eq 2) { $fields[[uri]::UnescapeDataString($kv[0])] = [uri]::UnescapeDataString($kv[1]) }
    }
    $txnRef = $fields['vnp_TxnRef']
    Assert-True (-not [string]::IsNullOrWhiteSpace($txnRef)) 'txnRef missing'
    $script:State['txnRef'] = $txnRef

    $ipnFields = [ordered]@{
        vnp_Amount = $fields['vnp_Amount']
        vnp_BankCode = 'NCB'
        vnp_BankTranNo = "BANK$((Get-Date).ToString('HHmmss'))"
        vnp_CardType = 'ATM'
        vnp_OrderInfo = $fields['vnp_OrderInfo']
        vnp_PayDate = (Get-Date).ToString('yyyyMMddHHmmss')
        vnp_ResponseCode = '00'
        vnp_TransactionNo = "TXN$((Get-Date).ToString('HHmmss'))"
        vnp_TransactionStatus = '00'
        vnp_TmnCode = 'TESTTMN01'
        vnp_TxnRef = $txnRef
    }
    # canonical query exactly like Java URLEncoder (space -> +, sorted keys, empty values skipped)
    $pairs = @()
    foreach ($k in ($ipnFields.Keys | Sort-Object)) {
        $v = [string]$ipnFields[$k]
        if ([string]::IsNullOrEmpty($v)) { continue }
        $pairs += ([uri]::EscapeDataString($k).Replace('%20', '+') + '=' + [uri]::EscapeDataString($v).Replace('%20', '+'))
    }
    $canonical = $pairs -join '&'
    $hmac = [System.Security.Cryptography.HMACSHA512]::new([System.Text.Encoding]::UTF8.GetBytes('TESTONLYSECRET0123456789'))
    $hash = ($hmac.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($canonical)) | ForEach-Object { $_.ToString('x2') }) -join ''
    $signedQuery = $canonical + '&vnp_SecureHash=' + $hash

    $ipn = Invoke-Api -Group 'booking' -Name 'VNPay IPN xác nhận thanh toán' -Method GET -Path "/payment/vnpay/ipn?$signedQuery" -Expect 200
    Assert-True ($ipn.Ok -and $ipn.Json.RspCode -eq '00') 'VNPay IPN did not confirm'
    $return = Invoke-Api -Group 'booking' -Name 'VNPay return (trình duyệt khách)' -Method GET -Path "/payment/vnpay/bookings/return?$signedQuery" -Expect 200
    Assert-True ($return.Ok -and $return.Json.result.alreadyProcessed -eq $true) 'VNPay return should report duplicate'

    $bkAfter = Invoke-Api -Group 'booking' -Name 'Booking sau thanh toán' -Method GET -Path "/booking/book/$bk1Id" -Token $u1 -Expect 200
    Assert-True ($bkAfter.Json.result.status -eq 'CONFIRMED') 'booking not confirmed after payment'

    $recon = Invoke-Api -Group 'booking' -Name 'Danh sách đối soát thanh toán' -Method GET -Path '/payment/reconciliation?page=0&view=ACTIONABLE' -Token $admin -Expect 200
    $reconHist = Invoke-Api -Group 'booking' -Name 'Lịch sử giao dịch đối soát' -Method GET -Path "/payment/reconciliation/$txnRef/history" -Token $admin -Expect 200

    # invoices
    $invByBooking = Invoke-Api -Group 'booking' -Name 'Hóa đơn theo booking vừa thanh toán' -Method GET -Path "/invoice/direct/booking/$bk1Id" -Token $u1 -Expect 200
    $invId = [string]$invByBooking.Json.result.id
    $invGet = Invoke-Api -Group 'booking' -Name 'Chi tiết hóa đơn' -Method GET -Path "/invoice/direct/$invId" -Token $u1 -Expect 200
    $invAll = Invoke-Api -Group 'booking' -Name 'Danh sách hóa đơn (admin)' -Method GET -Path '/invoice/direct/all?page=0&size=5' -Token $admin -Expect 200
    $cash = Invoke-Api -Group 'booking' -Name 'Tạo hóa đơn thu tiền mặt tại quầy (booking cũ 29)' -Method POST -Path '/invoice/direct/create' -Token $admin -Body @{
        bookingRequest = @{
            bookingId = '29'; userId = '3'; tourDepartureId = '641'
            listOfCustomers = @(@{ fullName = 'Khach le quay thu 1'; birthdate = '1995-04-12'; address = '12 Le Loi, Da Nang'; gender = 'true'; status = 'BOOKED'; bookingType = 'ADULT' })
        }
    } -Expect 200
    Assert-True $cash.Ok 'cash invoice create failed'
    $cashInv = Invoke-Api -Group 'booking' -Name 'Đọc lại hóa đơn quầy vừa tạo' -Method GET -Path '/invoice/direct/booking/29' -Token $admin -Expect 200

    # search
    $sync = Invoke-Api -Group 'booking' -Name 'Đồng bộ danh mục tìm kiếm' -Method POST -Path '/search/tours/sync' -Token $admin -Expect 200
    $sAll = Invoke-Api -Group 'booking' -Name 'Tìm kiếm: tất cả tour' -Method GET -Path '/search/tours?page=0&size=5' -Expect 200
    $sFilter = Invoke-Api -Group 'booking' -Name 'Tìm kiếm: lọc giá 5–10 triệu' -Method GET -Path '/search/tours/search?basePrice=5to10&page=0&size=5' -Expect 200

    # files
    $media = Invoke-Api -Group 'booking' -Name 'Tải ảnh công khai' -Method GET -Path '/file/media/download/7b623f38-2c91-4ef2-a45d-0d0100cec8f6' -Expect 200
    $mediaTours = Invoke-Api -Group 'booking' -Name 'Ảnh theo danh sách tour' -Method GET -Path '/file/media/tours?tourIds=1&tourIds=5' -Expect 200
    $owner = Invoke-Api -Group 'booking' -Name 'Tệp theo chủ sở hữu' -Method GET -Path '/file/owner/1' -Expect 200
    $orphans = Invoke-Api -Group 'booking' -Name 'Danh sách ảnh tour mồ côi' -Method GET -Path '/file/media/tour-orphans?page=0' -Token $admin -Expect 200

    # AI
    $policies = Invoke-Api -Group 'booking' -Name 'Chính sách AI (công khai)' -Method GET -Path '/ai/policies' -Expect 200
    $conv = Invoke-Api -Group 'booking' -Name 'Tạo phiên tư vấn AI' -Method POST -Path '/ai/conversations' -Body @{} -Expect 201
    Assert-True $conv.Ok 'create conversation failed'
    $convId = [string]$conv.Json.result.conversationId
    $reset = Invoke-Api -Group 'booking' -Name 'Đặt lại phiên tư vấn AI' -Method POST -Path '/ai/conversations/reset' -Body @{ conversationId = $convId } -Expect 200
    $helpAnon = Invoke-Api -Group 'booking' -Name 'Trợ giúp booking (ẩn danh, công khai)' -Method POST -Path '/ai/booking-help' -Body @{ message = 'toi da dat tour nay chua' } -Expect 200
    Assert-True ($helpAnon.Ok -and $helpAnon.Json.result.handled -eq $true) 'anonymous booking help failed'
    $helpAuth = Invoke-Api -Group 'booking' -Name 'Trợ giúp booking của tôi (đã đăng nhập)' -Method POST -Path '/ai/booking-help' -Token $u1 -Body @{ message = 'booking 22 con bao lau'; selectedBookingId = 22 } -Expect 200
    Assert-True ($helpAuth.Ok -and $helpAuth.Json.result.handled -eq $true) 'authenticated booking help failed'
    $aiHealth = Invoke-Api -Group 'booking' -Name 'Health AI service (gọi trực tiếp)' -Method GET -Path 'http://localhost:8091/ai/health' -Expect 200
    $socket = Invoke-Api -Group 'booking' -Name 'Bắt tay Socket.IO' -Method GET -Path 'http://localhost:8099/socket.io/?EIO=4&transport=polling' -Expect 200
    Assert-True ($socket.Ok -and $socket.Response.StartsWith('0')) 'socket handshake unexpected'

    Save-State
}

# ---------------- extra flows ----------------
function Run-Extra {
    $admin = $script:State['adminToken']
    $u1 = $script:State['user1Token']
    $u5 = $script:State['user5Token']
    if (-not $admin -or -not $u1 -or -not $u5) { throw 'tokens missing; run auth group first' }

    # MoMo quote: pricing is server-side, so it works even while MoMo checkout credentials are absent.
    $momoQuote = Invoke-Api -Group 'extra' -Name 'Báo giá thanh toán MoMo (booking chờ 27)' -Method GET -Path '/payment/momo/bookings/27/quote' -Token $u5 -Expect 200
    Assert-True ($momoQuote.Ok -and -not [string]::IsNullOrWhiteSpace([string]$momoQuote.Json.result.amount)) 'momo quote failed'

    # Reconciliation: close an expired unpaid VNPay transaction (operator happy path).
    $bk = Invoke-Api -Group 'extra' -Name 'Tạo booking phục vụ đối soát - lịch 611' -Method POST -Path '/booking/book/create' -Token $u1 -Body @{
        userId = '3'; tourDepartureId = '611'; clientKey = (New-Uuid)
        listOfCustomers = @(@{ fullName = 'Lê Văn Đối Soát'; birthdate = '1988-02-14'; address = '3 Pasteur, TP.HCM'; gender = 'Male'; status = 'BOOKED' })
    } -Expect 200
    Assert-True $bk.Ok 'extra booking create failed'
    $bkId = [string]$bk.Json.result.id

    $q = Invoke-Api -Group 'extra' -Name 'Báo giá VNPay cho booking đối soát' -Method GET -Path "/payment/vnpay/bookings/$bkId/quote" -Token $u1 -Expect 200
    $pay = Invoke-Api -Group 'extra' -Name 'Tạo giao dịch VNPay chờ (đối soát)' -Method POST -Path '/payment/vnpay/bookings' -Token $u1 -Body @{ bookingId = [int]$bkId; expectedAmount = [string]$q.Json.result.amount } -Expect 200
    Assert-True $pay.Ok 'extra vnpay create failed'
    $payQuery = ([uri]$pay.Response.Trim()).Query.TrimStart('?')
    $payRef = $null
    foreach ($pair in $payQuery.Split('&')) {
        $kv = $pair.Split('=', 2)
        if ($kv.Count -eq 2 -and [uri]::UnescapeDataString($kv[0]) -eq 'vnp_TxnRef') { $payRef = [uri]::UnescapeDataString($kv[1]) }
    }
    Assert-True (-not [string]::IsNullOrWhiteSpace($payRef)) 'extra txnRef missing'

    # Force the PENDING transaction past its deadline so the operator action is legal.
    $mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
    & $mysql --host=127.0.0.1 --port=3307 --user=root --default-character-set=utf8mb4 -e "USE dbtour_v2; UPDATE vnpay_transaction SET expires_at = NOW() - INTERVAL 1 MINUTE WHERE txn_ref='$payRef';" 2>&1 | Out-Null
    Assert-True ($LASTEXITCODE -eq 0) 'could not expire pending transaction'

    $resolve = Invoke-Api -Group 'extra' -Name 'Đối soát: xác nhận giao dịch hết hạn chưa thu tiền' -Method POST -Path "/payment/reconciliation/$payRef/resolve" -Token $admin -Body @{
        action = 'UNPAID_CONFIRMED'; amountVnd = [long]$q.Json.result.amount
        reference = "SGU-RECON-$((Get-Date).ToString('HHmmss'))"; note = 'Đã kiểm tra sao kê, khách chưa chuyển khoản'; confirmed = $true
    } -Expect 200
    Assert-True ($resolve.Ok -and $resolve.Json.result.status -eq 'CLOSED_UNPAID') 'reconciliation resolve failed'

    $reconHistory = Invoke-Api -Group 'extra' -Name 'Lịch sử đối soát của giao dịch vừa xử lý' -Method GET -Path "/payment/reconciliation/$payRef/history" -Token $admin -Expect 200
    Assert-True ($reconHistory.Ok -and @($reconHistory.Json.result).Count -ge 1) 'reconciliation history empty after resolve'

    $cancel = Invoke-Api -Group 'extra' -Name 'Hủy booking đối soát' -Method DELETE -Path "/booking/book/$bkId" -Token $u1 -Expect 200
    Assert-True ($cancel.Ok -and $cancel.Json.result.status -eq 'CANCELLED') 'extra booking cancel failed'

    $invMine = Invoke-Api -Group 'extra' -Name 'Hóa đơn của tôi' -Method GET -Path '/invoice/direct/all?page=0&size=10&mine=true' -Token $u1 -Expect 200
    Assert-True ($invMine.Ok -and @($invMine.Json.result).Count -ge 1) 'invoice mine list empty'

    Save-State
}

# ---------------- dispatch ----------------
if ($Group -eq 'auth' -or $Group -eq 'all') { Run-Auth }
if ($Group -eq 'tour' -or $Group -eq 'all') { Run-Tour }
if ($Group -eq 'booking' -or $Group -eq 'all') { Run-Booking }
if ($Group -eq 'extra' -or $Group -eq 'all') { Run-Extra }

# ---------------- persist results ----------------
foreach ($g in $script:ResultsByGroup.Keys) {
    $rows = $script:ResultsByGroup[$g] | Select-Object Group, Name, Method, Path, Status, Expected, Ok, ElapsedMs, Request, Response, Note, Time
    $json = $rows | ConvertTo-Json -Depth 5
    [IO.File]::WriteAllText((Join-Path $logDir "results-$g.json"), $json, [System.Text.UTF8Encoding]::new($false))
    $passed = @($rows | Where-Object { $_.Ok }).Count
    Write-Host "== $g : $passed/$($rows.Count) passed -> results-$g.json"
}
