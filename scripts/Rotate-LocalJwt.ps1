# Rotate this checkout's local JWT key. Never prints or backs up secret values.
[CmdletBinding(SupportsShouldProcess)]
param([string]$ConfigDirectory)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($ConfigDirectory)) {
    $ConfigDirectory = Join-Path (Split-Path -Parent $PSScriptRoot) '.local'
}
$modules = @('auth_service', 'booking_service', 'payment_service', 'pricing_service',
    'tour_service', 'file_service', 'invoice_service', 'search_service')
$configRoot = (Resolve-Path -LiteralPath $ConfigDirectory).Path
$utf8 = New-Object Text.UTF8Encoding($false)
$originals = @{}
$updated = @{}
$written = @()
$temporaryFiles = @()

# An environment override would keep the running services on another key.
foreach ($scope in @('Process', 'User', 'Machine')) {
    if (-not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable('JWT_SIGNER_KEY', $scope))) {
        throw "JWT_SIGNER_KEY is set in the $scope environment. Remove that override before rotating local files."
    }
}
foreach ($module in $modules) {
    $path = Join-Path $configRoot "$module.properties"
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing local configuration: $module.properties" }
    $text = [IO.File]::ReadAllText($path, $utf8)
    if ([regex]::Matches($text, '(?m)^JWT_SIGNER_KEY=[^\r\n]*').Count -ne 1) {
        throw "$module.properties must contain exactly one JWT_SIGNER_KEY assignment."
    }
    $originals[$path] = $text
}
if (-not $PSCmdlet.ShouldProcess('eight local service configurations', 'Rotate JWT key and invalidate previous tokens')) { return }

$bytes = New-Object byte[] 64
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
$key = [Convert]::ToBase64String($bytes)
try {
    # Prepare every replacement before changing any destination file.
    foreach ($path in $originals.Keys) {
        $text = [regex]::Replace($originals[$path], '(?m)^JWT_SIGNER_KEY=[^\r\n]*', "JWT_SIGNER_KEY=$key")
        $temp = Join-Path $configRoot ('.jwt-rotation-' + [Guid]::NewGuid().ToString('N') + '.tmp')
        $temporaryFiles += $temp
        [IO.File]::WriteAllText($temp, $text, $utf8)
        $updated[$path] = $temp
    }
    foreach ($path in $updated.Keys) {
        [IO.File]::Replace($updated[$path], $path, [NullString]::Value)
        $written += $path
    }
} catch {
    # Restore already-replaced files on a write failure. Originals stay in memory only.
    $rotationError = $_
    foreach ($path in $written) {
        $temp = Join-Path $configRoot ('.jwt-rotation-' + [Guid]::NewGuid().ToString('N') + '.tmp')
        $temporaryFiles += $temp
        [IO.File]::WriteAllText($temp, $originals[$path], $utf8)
        [IO.File]::Replace($temp, $path, [NullString]::Value)
    }
    throw $rotationError
} finally {
    foreach ($temp in $temporaryFiles) {
        if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Force }
    }
    [Array]::Clear($bytes, 0, $bytes.Length)
    $key = $null
    $originals.Clear()
    $updated.Clear()
}
Write-Output 'JWT key rotated for all eight services. Restart them together and sign in again.'
