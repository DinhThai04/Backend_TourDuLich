# Stops processes started by start-stack.ps1 (and optionally MySQL :3307).
# Usage: powershell -ExecutionPolicy Bypass -File .runtime\stop-stack.ps1 [-KeepMysql] [-Force]
param(
    [switch]$KeepMysql,
    [switch]$Force
)

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$logDir = Join-Path $here 'logs'
$pidFile = Join-Path $logDir 'stack-pids.txt'

if (Test-Path $pidFile) {
    foreach ($line in Get-Content $pidFile) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $parts = $line.Split('=')
        $name = $parts[0]; $procId = [int]$parts[1]
        $proc = Get-Process -Id $procId -ErrorAction SilentlyContinue
        if ($proc) {
            Stop-Process -Id $procId -Force
            Write-Host ("[stop] {0} pid={1}" -f $name, $procId)
        }
    }
    Remove-Item $pidFile -Force
}
else {
    Write-Host '[info] no stack-pids.txt; nothing to stop'
}

if (-not $KeepMysql) {
    $mysql = Get-NetTCPConnection -LocalPort 3307 -State Listen -ErrorAction SilentlyContinue |
        ForEach-Object { Get-Process -Id $_.OwningProcess -ErrorAction SilentlyContinue } | Select-Object -First 1
    if ($mysql) {
        if (-not $Force) { & "$env:SystemRoot\System32\mysqladmin.exe" --version 2>$null | Out-Null }
        Stop-Process -Id $mysql.Id -Force
        Write-Host ("[stop] mysqld pid={0}" -f $mysql.Id)
    }
}
