# Pre-flight check for the local SGU Tour environment.
# Read-only: never starts a service, never prints secret values.
# Usage (from the Backend_TourDuLich folder): powershell -ExecutionPolicy Bypass -File scripts\check-env.ps1
param(
    [switch]$SkipDocker,
    [switch]$SkipDependencies,
    [switch]$SkipSearch
)
$ErrorActionPreference = 'Continue'
$backendRoot = Split-Path -Parent $PSScriptRoot
$repoRoot = Split-Path -Parent $backendRoot
$failures = 0
$warnings = 0
$dockerEngineRunning = $false

function Write-Pass([string]$message) { Write-Host ("[PASS] " + $message) -ForegroundColor Green }
function Write-Warn([string]$message) { Write-Host ("[WARN] " + $message) -ForegroundColor Yellow; $script:warnings++ }
function Write-Fail([string]$message) { Write-Host ("[FAIL] " + $message) -ForegroundColor Red; $script:failures++ }
function Write-Section([string]$message) { Write-Host ''; Write-Host $message -ForegroundColor Cyan }

function Test-TcpPort([int]$port, [string]$target = '127.0.0.1') {
    $client = New-Object Net.Sockets.TcpClient
    try {
        $pending = $client.ConnectAsync($target, $port)
        return $pending.Wait(400) -and $client.Connected
    } catch { return $false } finally { $client.Dispose() }
}

function Get-PortOwner([int]$port) {
    try {
        $connection = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction Stop | Select-Object -First 1
        if ($connection) {
            $process = Get-Process -Id $connection.OwningProcess -ErrorAction SilentlyContinue
            if ($process) { return $process.ProcessName }
        }
    } catch { }
    try {
        $line = netstat -ano -p tcp | Select-String (":$port\s") | Select-Object -First 1
        if ($line) {
            $ownerId = ($line.ToString().Trim() -split '\s+')[-1]
            $process = Get-Process -Id $ownerId -ErrorAction SilentlyContinue
            if ($process) { return $process.ProcessName }
        }
    } catch { }
    return 'unknown'
}

function Get-RunningContainerName([string]$pattern) {
    if (-not $script:dockerEngineRunning) { return $null }
    try {
        $names = & docker ps --filter 'status=running' --format '{{.Names}}' 2>$null
        foreach ($name in $names) { if ($name -match $pattern) { return $name } }
    } catch { }
    return $null
}

Write-Host 'SGU Tour environment check' -ForegroundColor White
Write-Host ("Backend: " + $backendRoot)
Write-Host ("Frontend repo: " + (Join-Path $repoRoot 'Frontend_TourDuLich'))

if (-not $SkipDependencies) {
    Write-Section '1. Build tools'

    $java = Get-Command java -ErrorAction SilentlyContinue
    if (-not $java) {
        Write-Fail 'java not found. Install JDK 21 and add it to PATH.'
    } else {
        $javaOutput = & java -version 2>&1
        $javaLine = ($javaOutput | Select-Object -First 1).ToString()
        if ($javaLine -match '"(\d+)') {
            $javaMajor = [int]$Matches[1]
            if ($javaMajor -ge 21) { Write-Pass ("Java " + $javaMajor) }
            else { Write-Fail ("Java " + $javaMajor + " found; the backend needs JDK 21 or newer.") }
        } else {
            Write-Warn ("Could not parse java version: " + $javaLine)
        }
    }

    if (Get-Command mvn -ErrorAction SilentlyContinue) {
        $mavenOutput = & mvn -v 2>&1
        $mavenLine = ($mavenOutput | Select-Object -First 1).ToString()
        Write-Pass ("Maven: " + $mavenLine)
    } else {
        Write-Fail 'mvn not found. Install Maven 3.9.x or use a module wrapper.'
    }

    if (Get-Command node -ErrorAction SilentlyContinue) {
        $nodeVersion = (& node -v).ToString().TrimStart('v')
        $parts = $nodeVersion.Split('.')
        $nodeMajor = [int]$parts[0]
        $nodeMinor = [int]$parts[1]
        $nodeOk = ($nodeMajor -eq 20 -and $nodeMinor -ge 19) -or ($nodeMajor -gt 22) -or ($nodeMajor -eq 22 -and $nodeMinor -ge 12)
        if ($nodeOk) { Write-Pass ("Node " + $nodeVersion) }
        else { Write-Fail ("Node " + $nodeVersion + " found; Vite 7 needs ^20.19.0 or >=22.12.0.") }
    } else {
        Write-Fail 'node not found. Install Node 20.19+ or 22.12+ for the frontend.'
    }

    if (Get-Command npm -ErrorAction SilentlyContinue) { Write-Pass ("npm " + (& npm -v)) }
    else { Write-Fail 'npm not found.' }
}

Write-Section '2. Database and Docker'
if (Test-TcpPort 3306) {
    Write-Pass 'MySQL is listening on 127.0.0.1:3306'
} else {
    Write-Warn 'MySQL is not listening on 127.0.0.1:3306. Start XAMPP MySQL, or set DB_URL to your server.'
}

if (-not $SkipDocker) {
    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if (-not $docker) {
        Write-Warn 'docker not found. Native mode still works; Elasticsearch, Redis and n8n need Docker.'
    } else {
        $dockerVersion = & docker version --format '{{.Server.Version}}' 2>$null
        $dockerVersion | Out-Null
        if ($LASTEXITCODE -eq 0) {
            Write-Pass 'Docker Desktop is running'
            $dockerEngineRunning = $true
            Push-Location $backendRoot
            try {
                & docker compose -f docker-compose.yml config -q 2>$null | Out-Null
                if ($LASTEXITCODE -eq 0) { Write-Pass 'docker-compose.yml parses cleanly' }
                else { Write-Fail 'docker-compose.yml has a syntax/config error.' }
            } finally { Pop-Location }
        } else {
            Write-Warn 'docker command exists but the Docker engine is not running. Start Docker Desktop when you need ES/Redis/n8n.'
        }
    }
}

Write-Section '3. Local secrets (values are never printed)'
$localDir = Join-Path $backendRoot '.local'
$localConfig = @{}
if (Test-Path -LiteralPath $localDir) {
    Get-ChildItem -LiteralPath $localDir -Filter '*.properties' -File | ForEach-Object {
        Get-Content -LiteralPath $_.FullName | ForEach-Object {
            if ($_ -match '^\s*([^#=:\s]+)\s*[=:]\s*(.*)$') { $localConfig[$Matches[1]] = $Matches[2].Trim() }
        }
    }
} else {
    Write-Warn '.local folder not found; services will rely on IDE environment variables.'
}

function Get-Setting([string]$name) {
    $fromEnv = [Environment]::GetEnvironmentVariable($name, 'Process')
    if (-not [string]::IsNullOrWhiteSpace($fromEnv)) { return $fromEnv }
    if ($localConfig.ContainsKey($name)) { return $localConfig[$name] }
    return $null
}

$jwt = Get-Setting 'JWT_SIGNER_KEY'
if (-not $jwt) { Write-Fail 'JWT_SIGNER_KEY is missing; every service needs the same key.' }
elseif ($jwt.Length -lt 64) { Write-Fail ('JWT_SIGNER_KEY is only ' + $jwt.Length + ' characters; HS512 needs at least 64.') }
else { Write-Pass 'JWT_SIGNER_KEY is present and long enough' }

if ($localConfig.ContainsKey('DB_PASSWORD') -or -not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable('DB_PASSWORD', 'Process'))) {
    Write-Pass 'DB_PASSWORD is configured (an empty value is valid for a passwordless local MySQL)'
} else {
    Write-Warn 'DB_PASSWORD is not set; default root connection may fail if your MySQL has a password.'
}

if ((Get-Setting 'VNPAY_HASH_SECRET')) { Write-Pass 'VNPAY_HASH_SECRET is present' }
else { Write-Warn 'VNPAY_HASH_SECRET is missing; VNPay checkout will not work.' }

if ((Get-Setting 'VNPAY_TMN_CODE')) { Write-Pass 'VNPAY_TMN_CODE is present' }
else { Write-Warn 'VNPAY_TMN_CODE is missing; payment_service refuses to start without it.' }

if ((Get-Setting 'CLOUDINARY_API_KEY') -and (Get-Setting 'CLOUDINARY_API_SECRET')) { Write-Pass 'Cloudinary credentials are present' }
else { Write-Warn 'Cloudinary credentials are missing; image upload will not work.' }

$mailKey = Get-Setting 'MAIL_INTERNAL_API_KEY'
$mailSource = 'environment or .local'
$mailHelperConfig = Join-Path $env:LOCALAPPDATA 'J2eeTourMail/config.json'
if (-not $mailKey -and (Test-Path -LiteralPath $mailHelperConfig)) {
    try {
        $helperConfig = Get-Content -LiteralPath $mailHelperConfig -Raw | ConvertFrom-Json
        # The helper stores the key DPAPI-encrypted; decrypt only to validate length, never print it.
        $plainMailKey = [Net.NetworkCredential]::new('', (ConvertTo-SecureString ([string]$helperConfig.InternalKey))).Password
        if (-not [string]::IsNullOrWhiteSpace($plainMailKey)) { $mailKey = $plainMailKey; $mailSource = 'mail helper config' }
    } catch { $mailKey = $null }
}
if (-not [string]::IsNullOrWhiteSpace($mailKey)) {
    if ($mailKey.Length -lt 32) { Write-Warn ('MAIL_INTERNAL_API_KEY is only ' + $mailKey.Length + ' characters; gateway and booking require at least 32.') }
    else { Write-Pass ('MAIL_INTERNAL_API_KEY is present (' + $mailSource + ', ' + $mailKey.Length + ' chars)') }
} else { Write-Warn 'MAIL_INTERNAL_API_KEY is missing; n8n mail reminders will return 401/503. Run docs\mail\Setup-MailLocal.ps1 to create it.' }

$aiEnvPath = Join-Path $backendRoot 'ai_service/.env'
$groq = [Environment]::GetEnvironmentVariable('GROQ_API_KEY', 'Process')
if (-not $groq -and (Test-Path -LiteralPath $aiEnvPath)) {
    $groqLine = Get-Content -LiteralPath $aiEnvPath | Where-Object { $_ -match '^\s*GROQ_API_KEY\s*=' } | Select-Object -First 1
    if ($groqLine) { $groq = ($groqLine -split '=', 2)[1].Trim() }
}
if ($groq) { Write-Pass 'GROQ_API_KEY is present (AI model enabled)' }
else { Write-Warn 'GROQ_API_KEY is missing; the AI service still starts but the chatbot is disabled.' }

$frontendEnv = Join-Path $repoRoot 'Frontend_TourDuLich/.env'
if (Test-Path -LiteralPath $frontendEnv) {
    $apiLine = Get-Content -LiteralPath $frontendEnv | Where-Object { $_ -match '^\s*VITE_API_URL\s*=' } | Select-Object -First 1
    if (-not $apiLine) { Write-Pass 'Frontend .env has no VITE_API_URL; the app falls back to /api/v1.' }
    elseif ($apiLine -match '^\s*VITE_API_URL\s*=\s*$') { Write-Pass 'VITE_API_URL is empty; the app uses /api/v1 through the dev proxy.' }
    elseif ($apiLine -match '/api/v1') { Write-Pass 'Frontend VITE_API_URL points at the gateway or the /api/v1 proxy path' }
    else { Write-Warn 'VITE_API_URL should be empty, /api/v1, or an absolute URL ending in /api/v1.' }
} else {
    Write-Warn 'Frontend_TourDuLich/.env not found; copy .env.example when you first run the UI.'
}

Write-Section '4. Project ports'
$ports = 8080, 8081, 8083, 8084, 8086, 8087, 8089, 8090, 8091, 8099, 8888, 5173, 5678
$busy = 0
foreach ($port in $ports) {
    if (Test-TcpPort $port) {
        $owner = Get-PortOwner $port
        Write-Host ("[IN USE] " + $port + " -> " + $owner) -ForegroundColor DarkYellow
        $busy++
    }
}
if ($busy -eq 0) { Write-Pass 'All project ports are free' }
else { Write-Warn ('' + $busy + ' project port(s) already in use. Keep only one copy of each service; stop IntelliJ or Docker duplicates before starting another.') }

Write-Section '5. Published containers'
# nginx 80, Elasticsearch 9200 and Redis 6379 only exist in Docker, so a free port is
# normal unless the matching container is running. Search dependencies follow the
# compose "search" profile, hence -SkipSearch; -SkipDocker skips the whole section.
if ($SkipDocker) {
    Write-Pass 'Skipped (-SkipDocker): nginx 80, Elasticsearch 9200 and Redis 6379.'
} else {
    $publishedChecks = @(
        @{ Port = 80; Pattern = '(^|-)frontend(-|$)'; Label = 'nginx/frontend'; Search = $false },
        @{ Port = 9200; Pattern = '(^|-)elasticsearch(-|$)'; Label = 'Elasticsearch'; Search = $true },
        @{ Port = 6379; Pattern = '(^|-)redis(-|$)'; Label = 'Redis'; Search = $true }
    )
    foreach ($check in $publishedChecks) {
        if ($check.Search -and $SkipSearch) {
            Write-Pass ($check.Label + ' check skipped (-SkipSearch).')
            continue
        }
        $container = Get-RunningContainerName $check.Pattern
        if (-not $container) {
            Write-Host ("[INFO] " + $check.Label + ' container is not running; port ' + $check.Port + ' is not expected.') -ForegroundColor DarkGray
        } elseif (Test-TcpPort $check.Port) {
            Write-Pass ($check.Label + ' is published on 127.0.0.1:' + $check.Port)
        } else {
            Write-Warn ($check.Label + ' container is running but 127.0.0.1:' + $check.Port + ' is not reachable; check the compose port mapping.')
        }
    }
}

Write-Section 'Summary'
if ($failures -eq 0 -and $warnings -eq 0) { Write-Pass 'Environment looks ready.' }
else { Write-Host ("Failures: " + $failures + "; warnings: " + $warnings + ".") -ForegroundColor White }
if ($failures -gt 0) { exit 1 }
exit 0
