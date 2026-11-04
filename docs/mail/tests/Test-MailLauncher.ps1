# Offline regression checks. Uses dummy encrypted credentials and mocks network/process operations.
# Does not read the user's mail config, launch Java/Docker, access MySQL or send email.
$ErrorActionPreference = 'Stop'
$sourceScript = (Resolve-Path (Join-Path $PSScriptRoot '../Start-MailBackend.ps1')).Path
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$fixtureRoot = Join-Path $projectRoot ('booking_service/target/mail-launcher-test-' + [guid]::NewGuid().ToString('N'))
$fixtureScript = Join-Path $fixtureRoot 'docs/mail/Start-MailBackend.ps1'
$fixtureLocalData = Join-Path $fixtureRoot 'local-data'
$fixtureConfigDir = Join-Path $fixtureLocalData 'J2eeTourMail'
New-Item -ItemType Directory -Path (Split-Path $fixtureScript -Parent),$fixtureConfigDir -Force | Out-Null
Copy-Item -LiteralPath $sourceScript -Destination $fixtureScript
foreach ($service in @('booking_service','payment_service','invoice_service','api_gateway')) {
    $target = Join-Path $fixtureRoot "$service/target"
    New-Item -ItemType Directory -Path $target -Force | Out-Null
    'dummy jar; never executed' | Set-Content -LiteralPath (Join-Path $target "$service-0.0.1-SNAPSHOT.jar")
}
$configPath = Join-Path $fixtureConfigDir 'config.json'
$dummyKey = 'offline-test-key-0123456789abcdef0123456789'
$dummyPassword = 'offline-test-password'
$fixtureConfig = [pscustomobject]@{
    InternalKey = ConvertFrom-SecureString (ConvertTo-SecureString $dummyKey -AsPlainText -Force)
    SmtpPassword = ConvertFrom-SecureString (ConvertTo-SecureString $dummyPassword -AsPlainText -Force)
    Email = 'offline@example.com'
}
$fixtureConfig | ConvertTo-Json | Set-Content -LiteralPath $configPath -Encoding UTF8

# These functions are scoped to this test script; child launcher calls inherit the mocks.
function New-Object {
    param([string]$TypeName)
    if ($TypeName -ne 'Net.Sockets.TcpClient') { throw 'Unexpected object in launcher test' }
    $client = [pscustomobject]@{ Connected = $true }
    $client | Add-Member ScriptMethod ConnectAsync {
        param($address,$port)
        $pending = [pscustomobject]@{ Open = ($port -in $global:mailLauncherTestOpenPorts) }
        $pending | Add-Member ScriptMethod Wait { param($timeout); return $this.Open }
        return $pending
    }
    $client | Add-Member ScriptMethod Dispose {}
    return $client
}
function mvn { $global:LASTEXITCODE = 1 }
function docker { $global:LASTEXITCODE = 1 }
function Start-Process {
    param($FilePath,$ArgumentList,$WindowStyle,[switch]$PassThru,$WorkingDirectory,$RedirectStandardOutput,$RedirectStandardError)
    $global:mailLauncherTestStarts++
    if ($env:MAIL_INTERNAL_API_KEY -ne $dummyKey -or $env:MAIL_SMTP_PASSWORD -ne $dummyPassword) {
        throw 'Dummy credentials not passed to mocked child'
    }
    if ($global:mailLauncherTestStarts -eq $global:mailLauncherTestFailAt) { throw 'Simulated Java launch failure' }
    return [pscustomobject]@{ Id = 100000 + $global:mailLauncherTestStarts }
}

$names = @('MAIL_INTERNAL_API_KEY','MAIL_ENABLED','MAIL_TEST_MODE','MAIL_TEST_RECIPIENT','MAIL_FROM','MAIL_SMTP_USERNAME','MAIL_SMTP_PASSWORD')
$originalEnvironment = @{}
foreach ($name in $names + @('LOCALAPPDATA')) { $originalEnvironment[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
$passed = 0
try {
    $env:LOCALAPPDATA = $fixtureLocalData
    foreach ($scenario in @('mysql-down','port-busy','docker-fails','build-fails','decrypt-fails','launch-fails','state-write-fails','success')) {
        foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name,('original-' + $name),'Process') }
        # Also check restoration of a variable that was absent before startup.
        Remove-Item Env:MAIL_SMTP_PASSWORD -ErrorAction SilentlyContinue
        $global:mailLauncherTestOpenPorts = @(3306)
        $global:mailLauncherTestStarts = 0
        $global:mailLauncherTestFailAt = 0
        $arguments = @{}
        $statePath = Join-Path $fixtureConfigDir 'processes.json'
        if (Test-Path -LiteralPath $statePath -PathType Leaf) { Remove-Item -LiteralPath $statePath }
        if ($scenario -eq 'mysql-down') { $global:mailLauncherTestOpenPorts = @() }
        if ($scenario -eq 'port-busy') { $global:mailLauncherTestOpenPorts = @(3306,8081) }
        if ($scenario -eq 'docker-fails') { $arguments.StartN8n = $true }
        if ($scenario -eq 'build-fails') { $arguments.Build = $true }
        if ($scenario -eq 'decrypt-fails') {
            $fixtureConfig.InternalKey = 'invalid-encrypted-value'
            $fixtureConfig | ConvertTo-Json | Set-Content -LiteralPath $configPath -Encoding UTF8
        }
        if ($scenario -eq 'launch-fails') { $global:mailLauncherTestFailAt = 2 }
        if ($scenario -eq 'state-write-fails') { New-Item -ItemType Directory -Path $statePath -Force | Out-Null }
        $failure = $null
        try { & $fixtureScript @arguments } catch { $failure = $_ }
        if (($scenario -eq 'success') -eq ($null -ne $failure)) { throw "Unexpected outcome: $scenario" }
        foreach ($name in $names) {
            $expected = if ($name -eq 'MAIL_SMTP_PASSWORD') { $null } else { 'original-' + $name }
            if ([Environment]::GetEnvironmentVariable($name,'Process') -ne $expected) { throw "Environment not restored: $scenario / $name" }
        }
        if ($scenario -eq 'decrypt-fails') {
            $fixtureConfig.InternalKey = ConvertFrom-SecureString (ConvertTo-SecureString $dummyKey -AsPlainText -Force)
            $fixtureConfig | ConvertTo-Json | Set-Content -LiteralPath $configPath -Encoding UTF8
        }
        if ($scenario -eq 'state-write-fails') { Remove-Item -LiteralPath $statePath }
        if ($scenario -eq 'launch-fails') {
            $entries = @(Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json)
            if ($entries.Count -ne 1) { throw 'Partial launch PID was not retained' }
        }
        if ($scenario -eq 'success' -and $global:mailLauncherTestStarts -ne 4) { throw 'Expected four mocked service launches' }
        $passed++
        Write-Output "PASS: $scenario"
    }
} finally {
    foreach ($name in $originalEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name,$originalEnvironment[$name],'Process') }
    Remove-Variable mailLauncherTestOpenPorts,mailLauncherTestStarts,mailLauncherTestFailAt -Scope Global -ErrorAction SilentlyContinue
}
Write-Output "$passed launcher checks passed. Fixture retained at $fixtureRoot"
