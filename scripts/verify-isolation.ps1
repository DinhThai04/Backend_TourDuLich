#!/usr/bin/env powershell
<#
.SYNOPSIS
    Verify isolation - check for cross-database references in source code
.DESCRIPTION
    Scans Java source files for patterns that indicate cross-database access
    which violates database-per-service principle.
#>

param(
    [Parameter(Mandatory=$true)]
    [string]$BackendRoot = "Backend_TourDuLich",
    
    [switch]$FailOnError,
    
    [switch]$ShowContext
)

$Patterns = @(
    @{ Pattern = "dbtour_v2"; Description = "Hardcoded shared database name"; Severity = "ERROR" },
    @{ Pattern = "REFERENCES\s+\w+\."; Description = "Cross-table FK reference"; Severity = "WARN" },
    @{ Pattern = "JOIN\s+\w+\."; Description = "Cross-table JOIN"; Severity = "WARN" },
    @{ Pattern = "tour_departure\b"; Description = "Direct tour_departure access"; Severity = "ERROR" },
    @{ Pattern = "prices\b(?<!prices_)"; Description = "Direct prices table access"; Severity = "ERROR" },
    @{ Pattern = "booking\b(?<!booking_|bookings)"; Description = "Direct booking table access"; Severity = "ERROR" },
    @{ Pattern = "payment\b(?<!payment_)"; Description = "Direct payment table access"; Severity = "ERROR" },
    @{ Pattern = "invoice\b(?<!invoice_)"; Description = "Direct invoice table access"; Severity = "ERROR" },
    @{ Pattern = "file_mgmt\b"; Description = "Direct file_mgmt access"; Severity = "ERROR" },
    @{ Pattern = "mail_outbox\b"; Description = "Direct mail_outbox access"; Severity = "ERROR" },
    @{ Pattern = "vnpay_transaction\b"; Description = "Direct vnpay_transaction access"; Severity = "ERROR" },
    @{ Pattern = "momo_transaction\b"; Description = "Direct momo_transaction access"; Severity = "ERROR" },
    @{ Pattern = "payment_reconciliation\b"; Description = "Direct payment_reconciliation access"; Severity = "ERROR" },
    @{ Pattern = "booking_reservation\b"; Description = "Direct booking_reservation access"; Severity = "ERROR" },
    @{ Pattern = "customer\b(?<!customer_)"; Description = "Direct customer table access"; Severity = "ERROR" },
    @{ Pattern = "reviews\b"; Description = "Direct reviews table access"; Severity = "ERROR" },
    @{ Pattern = "invalidated_token\b"; Description = "Direct invalidated_token access"; Severity = "ERROR" },
    @{ Pattern = "user_account_event\b"; Description = "Direct user_account_event access"; Severity = "ERROR" },
    @{ Pattern = "permission_role\b"; Description = "Direct permission_role access"; Severity = "ERROR" },
    @{ Pattern = "booking_price_snapshot\b"; Description = "Direct booking_price_snapshot access"; Severity = "ERROR" },
    @{ Pattern = "booking_change\b"; Description = "Direct booking_change access"; Severity = "ERROR" },
    @{ Pattern = "departure_change\b"; Description = "Direct departure_change access"; Severity = "ERROR" },
    @{ Pattern = "itinerary\b"; Description = "Direct itinerary access"; Severity = "ERROR" },
    @{ Pattern = "location\b(?<!location_)"; Description = "Direct location table access"; Severity = "ERROR" },
    @{ Pattern = "vehicles\b"; Description = "Direct vehicles table access"; Severity = "ERROR" },
    @{ Pattern = "tour_image_ids\b"; Description = "Direct tour_image_ids access"; Severity = "ERROR" },
    @{ Pattern = "tour_media_lock\b"; Description = "Direct tour_media_lock access"; Severity = "ERROR" },
    @{ Pattern = "avatar_media_lock\b"; Description = "Direct avatar_media_lock access"; Severity = "ERROR" },
    @{ Pattern = "tour_departure\b"; Description = "Direct tour_departure access"; Severity = "ERROR" },
    @{ Pattern = "departure_hold\b"; Description = "Direct departure_hold access"; Severity = "ERROR" },
    @{ Pattern = "tour_price_view\b"; Description = "Direct tour_price_view access"; Severity = "ERROR" }
)

$AllowedFiles = @(
    "*/test/*",
    "*Test.java",
    "*Tests.java",
    "*TestDatabase.java",
    "*TestConfig.java",
    "LifecycleTestDatabase.java",
    "BookingAdviceReadOnlyMysqlTest.java",
    "LegacySeatTransactionTest.java"
)

function Test-IsAllowedFile {
    param([string]$Path)
    foreach ($pattern in $AllowedFiles) {
        if ($Path -like $pattern) { return $true }
    }
    return $false
}

Write-Host "=== Isolation Verification ===" -ForegroundColor Cyan
Write-Host "Scanning: $BackendRoot" -ForegroundColor Gray

$SourceFiles = Get-ChildItem -Path $BackendRoot -Recurse -Filter "*.java" -File |
    Where-Object { -not (Test-IsAllowedFile $_.FullName) }

Write-Host "Files to scan: $($SourceFiles.Count)" -ForegroundColor Gray

$TotalErrors = 0
$TotalWarnings = 0
$Results = @()

foreach ($file in $SourceFiles) {
    $Content = Get-Content $file.FullName -Raw -ErrorAction SilentlyContinue
    if (-not $Content) { continue }
    
    foreach ($p in $Patterns) {
        $Matches = [regex]::Matches($Content, $p.Pattern)
        if ($Matches.Count -gt 0) {
            foreach ($m in $Matches) {
                $line = ($Content.Substring(0, $m.Index) -split "`r?`n").Count
                $contextStart = [math]::Max(0, $m.Index - 80)
                $contextEnd = [math]::Min($Content.Length, $m.Index + $m.Length + 80)
                $context = $Content.Substring($contextStart, $contextEnd - $contextStart).Trim()
                
                $result = [PSCustomObject]@{
                    File = $file.FullName.Replace($BackendRoot + "\", "")
                    Line = $line
                    Pattern = $p.Pattern
                    Description = $p.Description
                    Severity = $p.Severity
                    Match = $m.Value
                    Context = $context
                }
                $Results += $result
                
                if ($p.Severity -eq "ERROR") { $TotalErrors++ } else { $TotalWarnings++ }
            }
        }
    }
}

# Group by file for cleaner output
$Grouped = $Results | Group-Object File

foreach ($group in $Grouped) {
    Write-Host "`n[$($group.Name)]" -ForegroundColor Yellow
    foreach ($r in $group.Group) {
        $color = if ($r.Severity -eq "ERROR") { "Red" } else { "DarkYellow" }
        Write-Host "  Line $($r.Line): [$($r.Severity)] $($r.Description) - '$($r.Match)'" -ForegroundColor $color
        if ($ShowContext) { Write-Host "    Context: $($r.Context)" -ForegroundColor Gray }
    }
}

Write-Host "`n=== Summary ===" -ForegroundColor Cyan
Write-Host "Files scanned: $($SourceFiles.Count)" -ForegroundColor Gray
Write-Host "Errors: $TotalErrors" -ForegroundColor Red
Write-Host "Warnings: $TotalWarnings" -ForegroundColor DarkYellow

if ($TotalErrors -gt 0 -and $FailOnError) {
    Write-Error "Isolation check FAILED with $TotalErrors errors"
    exit 1
} elseif ($TotalErrors -gt 0) {
    Write-Warning "Isolation check completed with $TotalErrors errors (not failing due to -FailOnError not set)"
    exit 0
} else {
    Write-Host "Isolation check PASSED" -ForegroundColor Green
    exit 0
}