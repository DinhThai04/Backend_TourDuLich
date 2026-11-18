$ErrorActionPreference = 'Stop'
$rotator = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../Rotate-LocalJwt.ps1')).Path
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$fixture = Join-Path $tempRoot ('sgu-jwt-test-' + [Guid]::NewGuid().ToString('N'))
$modules = @('auth_service', 'booking_service', 'payment_service', 'pricing_service',
    'tour_service', 'file_service', 'invoice_service', 'search_service')
$savedOverride = [Environment]::GetEnvironmentVariable('JWT_SIGNER_KEY', 'Process')
$utf8 = New-Object Text.UTF8Encoding($false)
function Assert([bool]$condition, [string]$message) { if (-not $condition) { throw $message } }
function Reset-Fixture {
    foreach ($module in $modules) {
        $path = Join-Path $fixture "$module.properties"
        if (Test-Path -LiteralPath $path) { (Get-Item -LiteralPath $path).IsReadOnly = $false }
        [IO.File]::WriteAllText($path, "# Fixture only`r`nJWT_SIGNER_KEY=$('fixture-key-' * 8)`r`nDB_PASSWORD=fixture-value`r`n", $utf8)
    }
}
function Snapshot {
    $hashes = @{}
    foreach ($module in $modules) {
        $path = Join-Path $fixture "$module.properties"
        if (Test-Path -LiteralPath $path) { $hashes[$module] = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash }
    }
    return $hashes
}
function Assert-Unchanged($before) {
    $after = Snapshot
    foreach ($module in $before.Keys) { Assert ($before[$module] -eq $after[$module]) 'Failed operation changed an existing file.' }
}
function Invoke-ExpectedFailure {
    $ErrorActionPreference = 'Continue'
    & powershell -NoProfile -File $rotator -ConfigDirectory $fixture *> $null
    return $LASTEXITCODE
}
New-Item -ItemType Directory -Path $fixture | Out-Null
try {
    [Environment]::SetEnvironmentVariable('JWT_SIGNER_KEY', $null, 'Process')
    Reset-Fixture
    $before = Snapshot
    & powershell -NoProfile -File $rotator -ConfigDirectory $fixture -WhatIf | Out-Null
    Assert ($LASTEXITCODE -eq 0) 'WhatIf failed.'
    Assert-Unchanged $before
    Write-Output 'PASS: WhatIf preserves all files'

    & powershell -NoProfile -File $rotator -ConfigDirectory $fixture | Out-Null
    Assert ($LASTEXITCODE -eq 0) 'Rotation failed.'
    $keys = @()
    foreach ($module in $modules) {
        $text = [IO.File]::ReadAllText((Join-Path $fixture "$module.properties"), $utf8)
        $match = [regex]::Match($text, '(?m)^JWT_SIGNER_KEY=([^\r\n]+)')
        Assert $match.Success 'Missing generated key.'
        $keys += $match.Groups[1].Value
        Assert ($text.Contains("DB_PASSWORD=fixture-value`r`n")) 'Other configuration changed.'
    }
    Assert (@($keys | Select-Object -Unique).Count -eq 1) 'Service keys differ.'
    Assert ([Convert]::FromBase64String($keys[0]).Length -eq 64) 'Key must originate from 64 random bytes.'
    Assert ($keys[0] -ne ('fixture-key-' * 8)) 'Key was not replaced.'
    Write-Output 'PASS: rotation shares one new 64-byte random key and preserves other properties'

    Reset-Fixture
    Remove-Item -LiteralPath (Join-Path $fixture 'search_service.properties')
    $before = Snapshot
    $exitCode = Invoke-ExpectedFailure
    Assert ($exitCode -ne 0) 'Missing configuration was accepted.'
    Assert-Unchanged $before
    Write-Output 'PASS: missing service fails without changing other files'

    Reset-Fixture
    Add-Content -LiteralPath (Join-Path $fixture 'auth_service.properties') -Value 'JWT_SIGNER_KEY=duplicate' -Encoding ascii
    $before = Snapshot
    $exitCode = Invoke-ExpectedFailure
    Assert ($exitCode -ne 0) 'Duplicate assignment was accepted.'
    Assert-Unchanged $before
    Write-Output 'PASS: duplicate assignment fails without writes'

    Reset-Fixture
    $before = Snapshot
    [Environment]::SetEnvironmentVariable('JWT_SIGNER_KEY', 'fixture-environment-override', 'Process')
    $exitCode = Invoke-ExpectedFailure
    Assert ($exitCode -ne 0) 'Environment override was accepted.'
    Assert-Unchanged $before
    [Environment]::SetEnvironmentVariable('JWT_SIGNER_KEY', $null, 'Process')
    Write-Output 'PASS: environment override fails without writes'

    Reset-Fixture
    $before = Snapshot
    (Get-Item -LiteralPath (Join-Path $fixture 'file_service.properties')).IsReadOnly = $true
    $exitCode = Invoke-ExpectedFailure
    Assert ($exitCode -ne 0) 'Read-only destination was unexpectedly replaced.'
    Assert-Unchanged $before
    Assert (@(Get-ChildItem -LiteralPath $fixture -Filter '.jwt-rotation-*.tmp' -Force).Count -eq 0) 'Temporary key file remained.'
    Write-Output 'PASS: write failure restores originals and removes temporary files'
} finally {
    [Environment]::SetEnvironmentVariable('JWT_SIGNER_KEY', $savedOverride, 'Process')
    $resolved = [IO.Path]::GetFullPath($fixture)
    if (-not $resolved.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($resolved) -notmatch '^sgu-jwt-test-[0-9a-f]{32}$') {
        throw 'Refusing cleanup outside the dedicated temporary fixture.'
    }
    Get-ChildItem -LiteralPath $resolved -File | ForEach-Object { $_.IsReadOnly = $false }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
