$ErrorActionPreference = 'Stop'
$statePath = Join-Path $env:LOCALAPPDATA 'J2eeTourMail/processes.json'
if (-not (Test-Path -LiteralPath $statePath)) { Write-Host 'No processes started by mail helper.'; return }
$entries = @(Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json)
foreach ($entry in $entries) {
    if ($null -eq $entry) { continue }
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$([int]$entry.Pid)" -ErrorAction SilentlyContinue
    # PID reuse must never kill a different process or an IDE-started service.
    if ($process -and $process.Name -eq 'java.exe' -and $process.CommandLine -and $process.CommandLine.Contains([string]$entry.Jar)) {
        Stop-Process -Id ([int]$entry.Pid)
        Write-Host "Stopped $($entry.Name)."
    }
}
'[]' | Set-Content -LiteralPath $statePath -Encoding UTF8
Write-Host 'Only Java processes started by the mail helper were stopped. MySQL, Docker and n8n are unchanged.'

