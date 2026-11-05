param([switch]$CopyKey)
$ErrorActionPreference = 'Stop'
$configDir = Join-Path $env:LOCALAPPDATA 'J2eeTourMail'
$configPath = Join-Path $configDir 'config.json'
New-Item -ItemType Directory -Path $configDir -Force | Out-Null
if (Test-Path -LiteralPath $configPath) {
    $config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
    $key = [Net.NetworkCredential]::new('', (ConvertTo-SecureString $config.InternalKey)).Password
} else {
    $random = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($random) } finally { $rng.Dispose() }
    $key = ([BitConverter]::ToString($random)).Replace('-', '').ToLowerInvariant()
    $config = [pscustomobject]@{ InternalKey = ''; SmtpPassword = ''; Email = '' }
}
$email = Read-Host "Gmail address used for SMTP/test mail (Enter to keep '$($config.Email)')"
if (-not [string]::IsNullOrWhiteSpace($email)) { $config.Email = $email.Trim() }
$secret = Read-Host 'Gmail App Password (Enter to keep existing / disable SMTP if none)' -AsSecureString
if ($secret.Length -gt 0) { $config.SmtpPassword = ConvertFrom-SecureString $secret }
$config.InternalKey = ConvertFrom-SecureString (ConvertTo-SecureString $key -AsPlainText -Force)
$config | ConvertTo-Json | Set-Content -LiteralPath $configPath -Encoding UTF8
if ($CopyKey) { Set-Clipboard -Value $key; Write-Host 'Internal key copied: paste it into n8n Header Auth credential.' }
Write-Host "Configuration saved with Windows user encryption: $configPath"
Write-Host 'Existing key is preserved on repeated setup. Do not share config, App Password or credential.'
Write-Host 'Next: stop old gateway/booking/payment/invoice, then run Start-MailBackend.ps1.'

