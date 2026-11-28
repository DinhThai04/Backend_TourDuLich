# Starts a second api_gateway on :8889 with a very small credential/AI budget so the opt-in
# rate-limit cases (429 + Retry-After) can be exercised without disturbing the main stack.
# Usage: powershell -ExecutionPolicy Bypass -File .runtime\start-rate-gateway.ps1
# Then:  powershell -ExecutionPolicy Bypass -File .runtime\api-error-test.ps1 -Group gw-auth -IncludeRateLimit
param(
    [int]$Port = 8889,
    [int]$LoginLimit = 3,
    [int]$RegisterLimit = 3,
    [int]$AiCreateLimit = 2,
    [int]$WaitSeconds = 120
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Split-Path -Parent $here
$logDir = Join-Path $here 'logs'
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }

# Same shared config the main stack uses (.local/*.properties), so tokens stay valid across gateways.
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

# Small budgets for the throttled gateway. AI values must stay inside AiTrafficLimiter's ranges.
$env:AUTH_LOGIN_LIMIT = [string]$LoginLimit
$env:AUTH_REGISTER_LIMIT = [string]$RegisterLimit
$env:AUTH_GOOGLE_LIMIT = '2'
$env:AUTH_REFRESH_LIMIT = '3'
$env:AUTH_PASSWORD_CHANGE_LIMIT = '2'
$env:AI_CREATE_LIMIT = [string]$AiCreateLimit
$env:AI_CHAT_MINUTE_LIMIT = '1'
$env:AI_CHAT_DAY_LIMIT = '10'
$env:AI_RESET_LIMIT = '1'
$env:AI_BOOKING_HELP_LIMIT = '2'
$env:MAIL_ENABLED = 'false'

# Replace any previous instance of the throttled gateway.
$existing = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
    ForEach-Object { Get-Process -Id $_.OwningProcess -ErrorAction SilentlyContinue } | Select-Object -First 1
if ($existing) {
    Stop-Process -Id $existing.Id -Force
    Start-Sleep -Seconds 2
}

$jarDir = Join-Path $root 'api_gateway'
$jar = Get-ChildItem (Join-Path $jarDir 'target/*.jar') -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $jar) { throw "no gateway jar in $jarDir\target" }

$out = Join-Path $logDir 'rate_gateway.out.log'
$err = Join-Path $logDir 'rate_gateway.err.log'
$portArg = '--server.port=' + $Port
$proc = Start-Process -FilePath 'java' -ArgumentList @('-Xmx384m', '-Dfile.encoding=UTF-8', '-jar', $jar.FullName, $portArg) `
    -WorkingDirectory $jarDir -RedirectStandardOutput $out -RedirectStandardError $err -WindowStyle Hidden -PassThru
Set-Content -Path (Join-Path $logDir 'rate-gateway.pid') -Value $proc.Id -Encoding ASCII

$deadline = (Get-Date).AddSeconds($WaitSeconds)
while ((Get-Date) -lt $deadline -and -not (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)) {
    Start-Sleep -Seconds 2
}
if (-not (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)) {
    throw "rate-limit gateway did not listen on $Port (see $err)"
}

$statePath = Join-Path $logDir 'err-state.properties'
$lines = @()
if (Test-Path $statePath) {
    $lines = @(Get-Content $statePath | Where-Object { $_ -notmatch '^rateGateway=' })
}
$lines += ('rateGateway=http://localhost:{0}/api/v1' -f $Port)
[IO.File]::WriteAllLines($statePath, $lines, [System.Text.UTF8Encoding]::new($false))

Write-Host ("[ok] throttled gateway pid={0} on http://localhost:{1}/api/v1 (login<=>{2}/min, register<={3}/min, ai-create<={4}/30min)" -f $proc.Id, $Port, $LoginLimit, $RegisterLimit, $AiCreateLimit)
