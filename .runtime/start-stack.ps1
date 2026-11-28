# Starts the local test stack (MySQL :3307 + 9 services + gateway) for the error-path suite.
# Usage: powershell -ExecutionPolicy Bypass -File .runtime\start-stack.ps1 [-ServicesOnly] [-Heap 512m]
param(
    [switch]$ServicesOnly,
    [string]$Heap = '512m',
    [int]$WaitSeconds = 180
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Split-Path -Parent $here
$logDir = Join-Path $here 'logs'
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }

# ---- shared secrets / config from .local/*.properties ----
Get-ChildItem (Join-Path $root '.local') -Filter '*.properties' -File | ForEach-Object {
    foreach ($line in Get-Content $_.FullName) {
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
            $name = $Matches[1]
            $value = $Matches[2].Trim()
            if (-not [Environment]::GetEnvironmentVariable($name, 'Process')) {
                [Environment]::SetEnvironmentVariable($name, $value, 'Process')
            }
        }
    }
}
$env:MAIL_ENABLED = 'false'

# The functional suite must not trip credential/AI throttles: raise them for the main run.
# Rate-limit behaviour is exercised separately by start-rate-gateway.ps1 on port 8889.
$defaults = @{
    AUTH_LOGIN_LIMIT            = '500'
    AUTH_REGISTER_LIMIT         = '500'
    AUTH_GOOGLE_LIMIT           = '500'
    AUTH_REFRESH_LIMIT          = '500'
    AUTH_PASSWORD_CHANGE_LIMIT  = '500'
    AI_CREATE_LIMIT             = '50'
    AI_CHAT_MINUTE_LIMIT        = '30'
    AI_CHAT_DAY_LIMIT           = '1000'
    AI_RESET_LIMIT              = '30'
    AI_BOOKING_HELP_LIMIT       = '60'
}
foreach ($k in $defaults.Keys) {
    if (-not [Environment]::GetEnvironmentVariable($k, 'Process')) {
        [Environment]::SetEnvironmentVariable($k, $defaults[$k], 'Process')
    }
}

function Test-Port([int]$port) {
    $c = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    return [bool]$c
}

# ---- MySQL test instance ----
$mysqld = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqld.exe'
if (-not $ServicesOnly) {
    if (Test-Port 3307) {
        Write-Host '[skip] MySQL already listening on 3307'
    }
    else {
        if (-not (Test-Path $mysqld)) { throw "mysqld not found at $mysqld" }
        Start-Process -FilePath $mysqld -ArgumentList "--defaults-file=$(Join-Path $here 'my-test.ini')" -WindowStyle Hidden
        for ($i = 0; $i -lt 30; $i++) {
            Start-Sleep -Milliseconds 500
            if (Test-Port 3307) { break }
        }
        if (-not (Test-Port 3307)) { throw 'MySQL did not start on 3307 (see .runtime/logs/mysql.log)' }
        Write-Host '[ok] MySQL listening on 3307'
    }
}

# ---- services: name, jar dir, port ----
$apps = @(
    @{ Name = 'auth_service';    Port = 8080; Heap = $Heap },
    @{ Name = 'tour_service';    Port = 8086; Heap = $Heap },
    @{ Name = 'booking_service'; Port = 8081; Heap = $Heap },
    @{ Name = 'payment_service'; Port = 8083; Heap = $Heap },
    @{ Name = 'pricing_service'; Port = 8084; Heap = $Heap },
    @{ Name = 'file_service';    Port = 8087; Heap = $Heap },
    @{ Name = 'search_service';  Port = 8089; Heap = $Heap },
    @{ Name = 'invoice_service'; Port = 8090; Heap = $Heap },
    @{ Name = 'ai_service';      Port = 8091; Heap = $Heap },
    @{ Name = 'api_gateway';     Port = 8888; Heap = '384m' }
)

$pids = @()
foreach ($app in $apps) {
    if (Test-Port $app.Port) {
        Write-Host ("[skip] {0} already listening on {1}" -f $app.Name, $app.Port)
        continue
    }
    $jarDir = Join-Path $root $app.Name
    $jar = Get-ChildItem (Join-Path $jarDir 'target/*.jar') -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $jar) { Write-Warning ("[warn] no jar in {0}\target" -f $app.Name); continue }
    $out = Join-Path $logDir ("{0}.out.log" -f $app.Name)
    $err = Join-Path $logDir ("{0}.err.log" -f $app.Name)
    $args = @("-Xmx$($app.Heap)", '-Dfile.encoding=UTF-8', '-jar', $jar.FullName)
    $proc = Start-Process -FilePath 'java' -ArgumentList $args -WorkingDirectory $jarDir -RedirectStandardOutput $out -RedirectStandardError $err -WindowStyle Hidden -PassThru
    $pids += [pscustomobject]@{ Name = $app.Name; Port = $app.Port; Pid = $proc.Id }
    Write-Host ("[start] {0} pid={1} port={2}" -f $app.Name, $proc.Id, $app.Port)
}

# Persist PIDs so stop-stack.ps1 can stop only what this script started.
$pidFile = Join-Path $logDir 'stack-pids.txt'
Set-Content -Path $pidFile -Value ($pids | ForEach-Object { "$($_.Name)=$($_.Pid)=$($_.Port)" }) -Encoding ASCII

# ---- wait for ports ----
$deadline = (Get-Date).AddSeconds($WaitSeconds)
foreach ($app in $apps) {
    while ((Get-Date) -lt $deadline -and -not (Test-Port $app.Port)) { Start-Sleep -Seconds 2 }
    $state = if (Test-Port $app.Port) { 'ok' } else { 'TIMEOUT' }
    Write-Host ("[{0}] {1} port {2}" -f $state, $app.Name, $app.Port)
}

$missing = $apps | Where-Object { -not (Test-Port $_.Port) }
if ($missing) {
    Write-Warning ("Not listening: " + (($missing | ForEach-Object { "$($_.Name):$($_.Port)" }) -join ', '))
    exit 1
}
Write-Host 'Stack is up.'
