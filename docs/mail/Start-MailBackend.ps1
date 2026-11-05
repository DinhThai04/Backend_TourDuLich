param([switch]$Build, [switch]$StartN8n)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$configDir = Join-Path $env:LOCALAPPDATA 'J2eeTourMail'
$configPath = Join-Path $configDir 'config.json'
if (-not (Test-Path -LiteralPath $configPath)) { throw 'Run Setup-MailLocal.ps1 first.' }
$config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
function Test-LocalPort([int]$Port) {
    $client = New-Object Net.Sockets.TcpClient
    try { $pending=$client.ConnectAsync('127.0.0.1',$Port); return $pending.Wait(400) -and $client.Connected }
    catch { return $false } finally { $client.Dispose() }
}
if (-not (Test-LocalPort 3306)) { throw 'Start MySQL in XAMPP and import data_seed/database_all.sql before starting mail backend.' }
if ($StartN8n) {
    # n8n cùng compose với backend, chỉ bật khi chọn profile mail.
    & docker compose -f (Join-Path $projectRoot 'docker-compose.yml') --profile mail up -d n8n
    if ($LASTEXITCODE -ne 0) { throw 'Start Docker Desktop before starting n8n.' }
}
$services = @(
    @{Name='booking_service';Port=8081}, @{Name='payment_service';Port=8083},
    @{Name='invoice_service';Port=8090}, @{Name='api_gateway';Port=8888}
)
foreach ($service in $services) {
    if (Test-LocalPort $service.Port) { throw "Port $($service.Port) is already used. Stop old $($service.Name) or use Stop-MailBackend.ps1, then rerun." }
}
foreach ($service in $services) {
    $serviceDir = Join-Path $projectRoot $service.Name
    $service.Jar = Join-Path $serviceDir ('target/' + $service.Name + '-0.0.1-SNAPSHOT.jar')
    if ($Build) {
        & mvn -B -f (Join-Path $serviceDir 'pom.xml') -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw "Build failed: $($service.Name)" }
    }
    if (-not (Test-Path -LiteralPath $service.Jar)) { throw 'Missing jar. Run this script with -Build.' }
}
$logDir = Join-Path $configDir 'logs'
New-Item -ItemType Directory -Path $logDir -Force | Out-Null
$started = @()
$smtpEnabled = if ([string]::IsNullOrWhiteSpace($config.SmtpPassword)) { 'false' } else { 'true' }
$mailEnvironmentNames = @('MAIL_INTERNAL_API_KEY','MAIL_ENABLED','MAIL_TEST_MODE','MAIL_TEST_RECIPIENT','MAIL_FROM','MAIL_SMTP_USERNAME','MAIL_SMTP_PASSWORD')
$previousMailEnvironment = @{}
foreach ($name in $mailEnvironmentNames) {
    $previousMailEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
try {
    # Set credentials only after preflight/build succeeds. Always restore the caller's environment.
    $env:MAIL_INTERNAL_API_KEY = [Net.NetworkCredential]::new('', (ConvertTo-SecureString $config.InternalKey)).Password
    $env:MAIL_ENABLED = $smtpEnabled
    $env:MAIL_TEST_MODE = 'true'
    $env:MAIL_TEST_RECIPIENT = $config.Email
    $env:MAIL_FROM = $config.Email
    $env:MAIL_SMTP_USERNAME = $config.Email
    if ($smtpEnabled -eq 'true') {
        $env:MAIL_SMTP_PASSWORD = [Net.NetworkCredential]::new('', (ConvertTo-SecureString $config.SmtpPassword)).Password
    } else {
        Remove-Item Env:MAIL_SMTP_PASSWORD -ErrorAction SilentlyContinue
    }
    foreach ($service in $services) {
        $process = Start-Process -FilePath (Get-Command java).Source -ArgumentList @('-jar', ('"{0}"' -f $service.Jar)) -WindowStyle Hidden -PassThru `
            -WorkingDirectory (Split-Path (Split-Path $service.Jar -Parent) -Parent) `
            -RedirectStandardOutput (Join-Path $logDir ($service.Name+'.out.log')) `
            -RedirectStandardError (Join-Path $logDir ($service.Name+'.err.log'))
        $started += [pscustomobject]@{ Name=$service.Name; Pid=$process.Id; Jar=$service.Jar }
        Write-Host "$($service.Name) launched as PID $($process.Id). Check logs for readiness."
    }
} finally {
    try {
        if ($started.Count -gt 0) {
            ConvertTo-Json -InputObject @($started) | Set-Content -LiteralPath (Join-Path $configDir 'processes.json') -Encoding UTF8
        }
    } finally {
        foreach ($name in $mailEnvironmentNames) {
            [Environment]::SetEnvironmentVariable($name, $previousMailEnvironment[$name], 'Process')
        }
    }
}
Write-Host "SMTP enabled: $smtpEnabled; test mailbox: $($config.Email). Logs: $logDir"
Write-Host 'Keep the services running for scheduled jobs. Closing this terminal does not stop Java; use Stop-MailBackend.ps1.'

