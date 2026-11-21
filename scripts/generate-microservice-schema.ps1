#!/usr/bin/env powershell
<#
.SYNOPSIS
    Generate 9 microservice schema files from database_all.sql
.DESCRIPTION
    Parses the monolithic database_all.sql and splits into 9 service-specific
    schema files with outbox/inbox tables added.
#>

param(
    [string]$InputFile = "data_seed/database_all.sql",
    [string]$OutputDir = "data_seed/microservices",
    [switch]$Force
)

if (-not (Test-Path $InputFile)) {
    Write-Error "Input file not found: $InputFile"
    exit 1
}

if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
    Write-Host "Created output directory: $OutputDir" -ForegroundColor Green
}

$Content = Get-Content $InputFile -Raw

# Extract all CREATE TABLE statements with their full definition
$bt = [char]0x60
$CreateTableRegex = '(?s)CREATE TABLE\s+(?:IF NOT EXISTS\s+)?' + $bt + '(\w+)' + $bt + '\s*\((.*?)\)\s*ENGINE=\w+.*?;'
$Matches = [regex]::Matches($Content, $CreateTableRegex)

$AllTables = @{}
foreach ($m in $Matches) {
    $tableName = $m.Groups[1].Value
    $tableDef = $m.Value
    $AllTables[$tableName] = $tableDef
}

Write-Host "Found $($AllTables.Count) tables in $InputFile" -ForegroundColor Cyan
$AllTables.Keys | Sort-Object | ForEach-Object { Write-Host "  $_" }

# Define ownership mapping
$Ownership = @{
    "sgu_auth" = @("roles", "permissions", "permission_role", "users", "invalidated_token", "user_account_event", "reviews")
    "sgu_tour" = @("location", "vehicles", "tour", "prices", "itinerary", "tour_departure", "tour_image_ids", "tour_media_lock", "departure_meeting_point")
    "sgu_pricing" = @("prices")
    "sgu_booking" = @("booking", "customer", "booking_reservation", "booking_price_snapshot", "booking_change", "booking_change_event", "departure_change")
    "sgu_payment" = @("payment", "vnpay_transaction", "momo_transaction", "payment_reconciliation", "payment_reconciliation_event")
    "sgu_invoice" = @("invoice")
    "sgu_file" = @("file_mgmt", "avatar_media_lock", "tour_media_lock")
    "sgu_notification" = @("mail_outbox")
    "sgu_reporting" = @()
}

# Extract INSERT statements for reference tables
$InsertRegex = '(?s)INSERT INTO\s+' + $bt + '(\w+)' + $bt + '\s+VALUES\s*(.*?);'
$InsertMatches = [regex]::Matches($Content, $InsertRegex)
$AllInserts = @{}
foreach ($m in $InsertMatches) {
    $table = $m.Groups[1].Value
    $stmt = $m.Value
    if (-not $AllInserts.ContainsKey($table)) { $AllInserts[$table] = @() }
    $AllInserts[$table] += $stmt
}

# Also look for multi-line INSERT with parentheses
$MultiInsertRegex = '(?s)INSERT INTO\s+' + $bt + '(\w+)' + $bt + '\s*\(.*?\)\s*VALUES\s*(.*?);'
$MultiMatches = [regex]::Matches($Content, $MultiInsertRegex)
foreach ($m in $MultiMatches) {
    $table = $m.Groups[1].Value
    $stmt = $m.Value
    if (-not $AllInserts.ContainsKey($table)) { $AllInserts[$table] = @() }
    $AllInserts[$table] += $stmt
}

function New-OutboxTable {
    param([string]$Service)
    $bt = [char]0x60
    $tbl = $bt + $Service + "_outbox" + $bt
    $sb = New-Object System.Text.StringBuilder
    $null = $sb.AppendLine("-- Outbox table for $Service")
    $null = $sb.AppendLine("CREATE TABLE $tbl (")
    $null = $sb.AppendLine("  ${bt}id${bt} BIGINT NOT NULL AUTO_INCREMENT,")
    $null = $sb.AppendLine("  ${bt}event_id${bt} VARCHAR(36) NOT NULL,")
    $null = $sb.AppendLine("  ${bt}event_type${bt} VARCHAR(120) NOT NULL,")
    $null = $sb.AppendLine("  ${bt}aggregate_type${bt} VARCHAR(80) NOT NULL,")
    $null = $sb.AppendLine("  ${bt}aggregate_id${bt} VARCHAR(80) NOT NULL,")
    $null = $sb.AppendLine("  ${bt}aggregate_version${bt} INT NOT NULL,")
    $null = $sb.AppendLine("  ${bt}correlation_id${bt} VARCHAR(80),")
    $null = $sb.AppendLine("  ${bt}causation_id${bt} VARCHAR(80),")
    $null = $sb.AppendLine("  ${bt}payload${bt} JSON NOT NULL,")
    $null = $sb.AppendLine("  ${bt}status${bt} VARCHAR(20) NOT NULL DEFAULT 'PENDING',")
    $null = $sb.AppendLine("  ${bt}attempts${bt} INT NOT NULL DEFAULT 0,")
    $null = $sb.AppendLine("  ${bt}available_at${bt} DATETIME(6) NOT NULL,")
    $null = $sb.AppendLine("  ${bt}published_at${bt} DATETIME(6) NULL,")
    $null = $sb.AppendLine("  ${bt}last_error${bt} VARCHAR(1000),")
    $null = $sb.AppendLine("  ${bt}created_at${bt} DATETIME(6) NOT NULL,")
    $null = $sb.AppendLine("  PRIMARY KEY (${bt}id${bt}),")
    $null = $sb.AppendLine("  UNIQUE KEY ${bt}uk_outbox_event_id${bt} (${bt}event_id${bt}),")
    $null = $sb.AppendLine("  KEY ${bt}idx_outbox_status_available${bt} (${bt}status${bt}, ${bt}available_at${bt})")
    $null = $sb.AppendLine(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;")
    return $sb.ToString()
}

function New-InboxTable {
    param([string]$Service)
    $bt = [char]0x60
    $tbl = $bt + $Service + "_inbox" + $bt
    $sb = New-Object System.Text.StringBuilder
    $null = $sb.AppendLine("-- Inbox table for $Service")
    $null = $sb.AppendLine("CREATE TABLE $tbl (")
    $null = $sb.AppendLine("  ${bt}consumer${bt} VARCHAR(120) NOT NULL,")
    $null = $sb.AppendLine("  ${bt}event_id${bt} VARCHAR(36) NOT NULL,")
    $null = $sb.AppendLine("  ${bt}event_type${bt} VARCHAR(120) NOT NULL,")
    $null = $sb.AppendLine("  ${bt}received_at${bt} DATETIME(6) NOT NULL,")
    $null = $sb.AppendLine("  ${bt}processed_at${bt} DATETIME(6) NULL,")
    $null = $sb.AppendLine("  PRIMARY KEY (${bt}consumer${bt}, ${bt}event_id${bt}),")
    $null = $sb.AppendLine("  KEY ${bt}idx_inbox_unprocessed${bt} (${bt}processed_at${bt})")
    $null = $sb.AppendLine(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;")
    return $sb.ToString()
}

# Build ExtraTables
$ExtraTables = @{}
$bt = [char]0x60

function Add-Table {
    param([string]$Name, [scriptblock]$Script)
    $sb = New-Object System.Text.StringBuilder
    & $Script $sb $bt
    $ExtraTables[$Name] = $sb.ToString()
}

Add-Table "sgu_tour" {
    param($sb, $bt)
    $null = $sb.AppendLine("-- departure_hold: capacity hold for bookings")
    $null = $sb.AppendLine("CREATE TABLE ${bt}departure_hold${bt} (")
    $null = $sb.AppendLine("  ${bt}hold_id${bt} VARCHAR(36) NOT NULL,")
    $null = $sb.AppendLine("  ${bt}departure_id${bt} INT NOT NULL,")
    $null = $sb.AppendLine("  ${bt}booking_id${bt} INT NOT NULL,")
    $null = $sb.AppendLine("  ${bt}seats${bt} INT NOT NULL,")
    $null = $sb.AppendLine("  ${bt}status${bt} VARCHAR(20) NOT NULL, -- HELD, RELEASED, EXPIRED")
    $null = $sb.AppendLine("  ${bt}expires_at${bt} DATETIME(6) NOT NULL,")
    $null = $sb.AppendLine("  ${bt}version${bt} BIGINT NOT NULL DEFAULT 0,")
    $null = $sb.AppendLine("  ${bt}created_at${bt} DATETIME(6) NOT NULL,")
    $null = $sb.AppendLine("  PRIMARY KEY (${bt}hold_id${bt}),")
    $null = $sb.AppendLine("  KEY ${bt}idx_hold_departure${bt} (${bt}departure_id${bt}),")
    $null = $sb.AppendLine("  KEY ${bt}idx_hold_booking${bt} (${bt}booking_id${bt}),")
    $null = $sb.AppendLine("  KEY ${bt}idx_hold_expires${bt} (${bt}expires_at${bt}, ${bt}status${bt})")
    $null = $sb.AppendLine(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;")
}

Add-Table "sgu_file" {
    param($sb, $bt)
    $null = $sb.AppendLine("-- file_reference: reference tracking for file ownership")
    $null = $sb.AppendLine("CREATE TABLE ${bt}file_reference${bt} (")
    $null = $sb.AppendLine("  ${bt}id${bt} BIGINT NOT NULL AUTO_INCREMENT,")
    $null = $sb.AppendLine("  ${bt}file_id${bt} VARCHAR(36) NOT NULL,")
    $null = $sb.AppendLine("  ${bt}owner_type${bt} VARCHAR(20) NOT NULL, -- TOUR, AVATAR, BOOKING")
    $null = $sb.AppendLine("  ${bt}owner_id${bt} VARCHAR(80) NOT NULL,")
    $null = $sb.AppendLine("  ${bt}created_at${bt} DATETIME(6) NOT NULL,")
    $null = $sb.AppendLine("  PRIMARY KEY (${bt}id${bt}),")
    $null = $sb.AppendLine("  UNIQUE KEY ${bt}uk_file_owner${bt} (${bt}file_id${bt}, ${bt}owner_type${bt}, ${bt}owner_id${bt}),")
    $null = $sb.AppendLine("  KEY ${bt}idx_file_ref_owner${bt} (${bt}owner_type${bt}, ${bt}owner_id${bt})")
    $null = $sb.AppendLine(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;")
}

Add-Table "sgu_booking" {
    param($sb, $bt)
    $null = $sb.AppendLine("-- booking_saga: saga state for booking hold/reschedule")
    $null = $sb.AppendLine("CREATE TABLE ${bt}booking_saga${bt} (")
    $null = $sb.AppendLine("  ${bt}id${bt} VARCHAR(36) NOT NULL,")
    $null = $sb.AppendLine("  ${bt}saga_type${bt} VARCHAR(80) NOT NULL, -- HOLD, RESCHEDULE, REFUND")
    $null = $sb.AppendLine("  ${bt}aggregate_id${bt} VARCHAR(80) NOT NULL,")
    $null = $sb.AppendLine("  ${bt}status${bt} VARCHAR(30) NOT NULL, -- STARTED, STEP_COMPLETED, COMPENSATING, COMPLETED, FAILED")
    $null = $sb.AppendLine("  ${bt}current_step${bt} VARCHAR(120) NOT NULL,")
    $null = $sb.AppendLine("  ${bt}payload${bt} JSON NOT NULL,")
    $null = $sb.AppendLine("  ${bt}attempts${bt} INT NOT NULL DEFAULT 0,")
    $null = $sb.AppendLine("  ${bt}last_error${bt} VARCHAR(1000),")
    $null = $sb.AppendLine("  ${bt}created_at${bt} DATETIME(6) NOT NULL,")
    $null = $sb.AppendLine("  ${bt}updated_at${bt} DATETIME(6) NOT NULL,")
    $null = $sb.AppendLine("  PRIMARY KEY (${bt}id${bt}),")
    $null = $sb.AppendLine("  KEY ${bt}idx_saga_aggregate${bt} (${bt}aggregate_id${bt}),")
    $null = $sb.AppendLine("  KEY ${bt}idx_saga_status${bt} (${bt}status${bt})")
    $null = $sb.AppendLine(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;")
}

Add-Table "sgu_payment" {
    param($sb, $bt)
    $null = $sb.AppendLine("-- payment_saga: saga state for payment/refund")
    $null = $sb.AppendLine("CREATE TABLE ${bt}payment_saga${bt} (")
    $null = $sb.AppendLine("  ${bt}id${bt} VARCHAR(36) NOT NULL,")
    $null = $sb.AppendLine("  ${bt}saga_type${bt} VARCHAR(80) NOT NULL, -- SETTLEMENT, REFUND")
    $null = $sb.AppendLine("  ${bt}aggregate_id${bt} VARCHAR(80) NOT NULL,")
    $null = $sb.AppendLine("  ${bt}status${bt} VARCHAR(30) NOT NULL,")
    $null = $sb.AppendLine("  ${bt}current_step${bt} VARCHAR(120) NOT NULL,")
    $null = $sb.AppendLine("  ${bt}payload${bt} JSON NOT NULL,")
    $null = $sb.AppendLine("  ${bt}attempts${bt} INT NOT NULL DEFAULT 0,")
    $null = $sb.AppendLine("  ${bt}last_error${bt} VARCHAR(1000),")
    $null = $sb.AppendLine("  ${bt}created_at${bt} DATETIME(6) NOT NULL,")
    $null = $sb.AppendLine("  ${bt}updated_at${bt} DATETIME(6) NOT NULL,")
    $null = $sb.AppendLine("  PRIMARY KEY (${bt}id${bt}),")
    $null = $sb.AppendLine("  KEY ${bt}idx_saga_aggregate${bt} (${bt}aggregate_id${bt}),")
    $null = $sb.AppendLine("  KEY ${bt}idx_saga_status${bt} (${bt}status${bt})")
    $null = $sb.AppendLine(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;")
}

Add-Table "sgu_notification" {
    param($sb, $bt)
    $null = $sb.AppendLine("-- mail_template")
    $null = $sb.AppendLine("CREATE TABLE ${bt}mail_template${bt} (")
    $null = $sb.AppendLine("  ${bt}id${bt} VARCHAR(36) NOT NULL,")
    $null = $sb.AppendLine("  ${bt}code${bt} VARCHAR(80) NOT NULL,")
    $null = $sb.AppendLine("  ${bt}subject${bt} VARCHAR(200) NOT NULL,")
    $null = $sb.AppendLine("  ${bt}body_html${bt} TEXT NOT NULL,")
    $null = $sb.AppendLine("  ${bt}body_text${bt} TEXT,")
    $null = $sb.AppendLine("  ${bt}variables${bt} JSON,")
    $null = $sb.AppendLine("  ${bt}created_at${bt} DATETIME(6) NOT NULL,")
    $null = $sb.AppendLine("  ${bt}updated_at${bt} DATETIME(6) NOT NULL,")
    $null = $sb.AppendLine("  PRIMARY KEY (${bt}id${bt}),")
    $null = $sb.AppendLine("  UNIQUE KEY ${bt}uk_template_code${bt} (${bt}code${bt})")
    $null = $sb.AppendLine(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;")
    $null = $sb.AppendLine("")
    $null = $sb.AppendLine("-- mail_delivery_attempt")
    $null = $sb.AppendLine("CREATE TABLE ${bt}mail_delivery_attempt${bt} (")
    $null = $sb.AppendLine("  ${bt}id${bt} BIGINT NOT NULL AUTO_INCREMENT,")
    $null = $sb.AppendLine("  ${bt}outbox_id${bt} BIGINT NOT NULL,")
    $null = $sb.AppendLine("  ${bt}attempt_no${bt} INT NOT NULL,")
    $null = $sb.AppendLine("  ${bt}status${bt} VARCHAR(20) NOT NULL, -- SENT, FAILED, RETRY")
    $null = $sb.AppendLine("  ${bt}error${bt} VARCHAR(1000),")
    $null = $sb.AppendLine("  ${bt}sent_at${bt} DATETIME(6) NOT NULL,")
    $null = $sb.AppendLine("  PRIMARY KEY (${bt}id${bt}),")
    $null = $sb.AppendLine("  KEY ${bt}idx_attempt_outbox${bt} (${bt}outbox_id${bt})")
    $null = $sb.AppendLine(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;")
    $null = $sb.AppendLine("")
    $null = $sb.AppendLine("-- mail_suppression")
    $null = $sb.AppendLine("CREATE TABLE ${bt}mail_suppression${bt} (")
    $null = $sb.AppendLine("  ${bt}email${bt} VARCHAR(200) NOT NULL,")
    $null = $sb.AppendLine("  ${bt}reason${bt} VARCHAR(100) NOT NULL, -- BOUNCE, COMPLAINT, UNSUBSCRIBE")
    $null = $sb.AppendLine("  ${bt}created_at${bt} DATETIME(6) NOT NULL,")
    $null = $sb.AppendLine("  PRIMARY KEY (${bt}email${bt})")
    $null = $sb.AppendLine(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;")
}

Add-Table "sgu_reporting" {
    param($sb, $bt)
    $null = $sb.AppendLine("-- operation_audit: unified audit timeline")
    $null = $sb.AppendLine("CREATE TABLE ${bt}operation_audit${bt} (")
    $null = $sb.AppendLine("  ${bt}id${bt} BIGINT NOT NULL AUTO_INCREMENT,")
    $null = $sb.AppendLine("  ${bt}actor_id${bt} VARCHAR(80) NOT NULL,")
    $null = $sb.AppendLine("  ${bt}action${bt} VARCHAR(120) NOT NULL,")
    $null = $sb.AppendLine("  ${bt}entity_type${bt} VARCHAR(80) NOT NULL,")
    $null = $sb.AppendLine("  ${bt}entity_id${bt} VARCHAR(80) NOT NULL,")
    $null = $sb.AppendLine("  ${bt}detail${bt} JSON,")
    $null = $sb.AppendLine("  ${bt}correlation_id${bt} VARCHAR(80),")
    $null = $sb.AppendLine("  ${bt}causation_id${bt} VARCHAR(80),")
    $null = $sb.AppendLine("  ${bt}occurred_at${bt} DATETIME(6) NOT NULL,")
    $null = $sb.AppendLine("  PRIMARY KEY (${bt}id${bt}),")
    $null = $sb.AppendLine("  KEY ${bt}idx_audit_actor${bt} (${bt}actor_id${bt}),")
    $null = $sb.AppendLine("  KEY ${bt}idx_audit_entity${bt} (${bt}entity_type${bt}, ${bt}entity_id${bt}),")
    $null = $sb.AppendLine("  KEY ${bt}idx_audit_time${bt} (${bt}occurred_at${bt}),")
    $null = $sb.AppendLine("  KEY ${bt}idx_audit_correlation${bt} (${bt}correlation_id${bt})")
    $null = $sb.AppendLine(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;")
    $null = $sb.AppendLine("")
    $null = $sb.AppendLine("-- ops_booking: read model for admin booking dashboard")
    $null = $sb.AppendLine("CREATE TABLE ${bt}ops_booking${bt} (")
    $null = $sb.AppendLine("  ${bt}booking_id${bt} INT NOT NULL,")
    $null = $sb.AppendLine("  ${bt}account_id${bt} BIGINT NOT NULL,")
    $null = $sb.AppendLine("  ${bt}departure_id${bt} INT NOT NULL,")
    $null = $sb.AppendLine("  ${bt}tour_id${bt} INT NOT NULL,")
    $null = $sb.AppendLine("  ${bt}tour_code${bt} VARCHAR(40),")
    $null = $sb.AppendLine("  ${bt}status${bt} VARCHAR(20) NOT NULL,")
    $null = $sb.AppendLine("  ${bt}seats${bt} INT NOT NULL,")
    $null = $sb.AppendLine("  ${bt}amount${bt} BIGINT NOT NULL,")
    $null = $sb.AppendLine("  ${bt}created_at${bt} DATETIME(6) NOT NULL,")
    $null = $sb.AppendLine("  ${bt}confirmed_at${bt} DATETIME(6),")
    $null = $sb.AppendLine("  ${bt}cancelled_at${bt} DATETIME(6),")
    $null = $sb.AppendLine("  ${bt}last_updated${bt} DATETIME(6) NOT NULL,")
    $null = $sb.AppendLine("  PRIMARY KEY (${bt}booking_id${bt}),")
    $null = $sb.AppendLine("  KEY ${bt}idx_ops_book_account${bt} (${bt}account_id${bt}),")
    $null = $sb.AppendLine("  KEY ${bt}idx_ops_book_departure${bt} (${bt}departure_id${bt}),")
    $null = $sb.AppendLine("  KEY ${bt}idx_ops_book_status${bt} (${bt}status${bt}),")
    $null = $sb.AppendLine("  KEY ${bt}idx_ops_book_time${bt} (${bt}created_at${bt})")
    $null = $sb.AppendLine(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;")
    $null = $sb.AppendLine("")
    $null = $sb.AppendLine("-- ops_payment")
    $null = $sb.AppendLine("CREATE TABLE ${bt}ops_payment${bt} (")
    $null = $sb.AppendLine("  ${bt}payment_id${bt} INT NOT NULL,")
    $null = $sb.AppendLine("  ${bt}booking_id${bt} INT NOT NULL,")
    $null = $sb.AppendLine("  ${bt}account_id${bt} BIGINT NOT NULL,")
    $null = $sb.AppendLine("  ${bt}provider${bt} VARCHAR(20) NOT NULL,")
    $null = $sb.AppendLine("  ${bt}status${bt} VARCHAR(20) NOT NULL,")
    $null = $sb.AppendLine("  ${bt}amount${bt} BIGINT NOT NULL,")
    $null = $sb.AppendLine("  ${bt}txn_ref${bt} VARCHAR(80),")
    $null = $sb.AppendLine("  ${bt}paid_at${bt} DATETIME(6),")
    $null = $sb.AppendLine("  ${bt}created_at${bt} DATETIME(6) NOT NULL,")
    $null = $sb.AppendLine("  PRIMARY KEY (${bt}payment_id${bt}),")
    $null = $sb.AppendLine("  KEY ${bt}idx_ops_pay_booking${bt} (${bt}booking_id${bt}),")
    $null = $sb.AppendLine("  KEY ${bt}idx_ops_pay_account${bt} (${bt}account_id${bt}),")
    $null = $sb.AppendLine("  KEY ${bt}idx_ops_pay_status${bt} (${bt}status${bt}),")
    $null = $sb.AppendLine("  KEY ${bt}idx_ops_pay_time${bt} (${bt}paid_at${bt})")
    $null = $sb.AppendLine(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;")
    $null = $sb.AppendLine("")
    $null = $sb.AppendLine("-- ops_invoice")
    $null = $sb.AppendLine("CREATE TABLE ${bt}ops_invoice${bt} (")
    $null = $sb.AppendLine("  ${bt}invoice_id${bt} INT NOT NULL,")
    $null = $sb.AppendLine("  ${bt}booking_id${bt} INT NOT NULL,")
    $null = $sb.AppendLine("  ${bt}account_id${bt} BIGINT NOT NULL,")
    $null = $sb.AppendLine("  ${bt}status${bt} VARCHAR(20) NOT NULL,")
    $null = $sb.AppendLine("  ${bt}amount${bt} BIGINT NOT NULL,")
    $null = $sb.AppendLine("  ${bt}issued_at${bt} DATETIME(6) NOT NULL,")
    $null = $sb.AppendLine("  ${bt}paid_at${bt} DATETIME(6),")
    $null = $sb.AppendLine("  PRIMARY KEY (${bt}invoice_id${bt}),")
    $null = $sb.AppendLine("  KEY ${bt}idx_ops_inv_booking${bt} (${bt}booking_id${bt}),")
    $null = $sb.AppendLine("  KEY ${bt}idx_ops_inv_account${bt} (${bt}account_id${bt}),")
    $null = $sb.AppendLine("  KEY ${bt}idx_ops_inv_status${bt} (${bt}status${bt})")
    $null = $sb.AppendLine(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;")
    $null = $sb.AppendLine("")
    $null = $sb.AppendLine("-- ops_tour")
    $null = $sb.AppendLine("CREATE TABLE ${bt}ops_tour${bt} (")
    $null = $sb.AppendLine("  ${bt}tour_id${bt} INT NOT NULL,")
    $null = $sb.AppendLine("  ${bt}tour_code${bt} VARCHAR(40),")
    $null = $sb.AppendLine("  ${bt}departure_id${bt} INT NOT NULL,")
    $null = $sb.AppendLine("  ${bt}departure_date${bt} DATETIME(6) NOT NULL,")
    $null = $sb.AppendLine("  ${bt}capacity${bt} INT,")
    $null = $sb.AppendLine("  ${bt}available_seats${bt} INT,")
    $null = $sb.AppendLine("  ${bt}operational_status${bt} VARCHAR(20),")
    $null = $sb.AppendLine("  ${bt}booked_seats${bt} INT DEFAULT 0,")
    $null = $sb.AppendLine("  ${bt}held_seats${bt} INT DEFAULT 0,")
    $null = $sb.AppendLine("  ${bt}last_updated${bt} DATETIME(6) NOT NULL,")
    $null = $sb.AppendLine("  PRIMARY KEY (${bt}tour_id${bt}, ${bt}departure_id${bt}),")
    $null = $sb.AppendLine("  KEY ${bt}idx_ops_tour_date${bt} (${bt}departure_date${bt}),")
    $null = $sb.AppendLine("  KEY ${bt}idx_ops_tour_status${bt} (${bt}operational_status${bt})")
    $null = $sb.AppendLine(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;")
    $null = $sb.AppendLine("")
    $null = $sb.AppendLine("-- ops_user")
    $null = $sb.AppendLine("CREATE TABLE ${bt}ops_user${bt} (")
    $null = $sb.AppendLine("  ${bt}user_id${bt} BIGINT NOT NULL,")
    $null = $sb.AppendLine("  ${bt}email${bt} VARCHAR(200),")
    $null = $sb.AppendLine("  ${bt}status${bt} VARCHAR(20) NOT NULL,")
    $null = $sb.AppendLine("  ${bt}role${bt} VARCHAR(80),")
    $null = $sb.AppendLine("  ${bt}total_bookings${bt} INT DEFAULT 0,")
    $null = $sb.AppendLine("  ${bt}total_spent${bt} BIGINT DEFAULT 0,")
    $null = $sb.AppendLine("  ${bt}last_active${bt} DATETIME(6),")
    $null = $sb.AppendLine("  PRIMARY KEY (${bt}user_id${bt}),")
    $null = $sb.AppendLine("  KEY ${bt}idx_ops_user_status${bt} (${bt}status${bt})")
    $null = $sb.AppendLine(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;")
}

# Clean cross-database FK from table definition
function Clean-TableDef {
    param([string]$Def)
    $Def = $Def -replace '(?i)REFERENCES\s+\w+\.\s*`\w+`\s*\(', 'REFERENCES `'
    $Def = $Def -replace '(?i)FOREIGN KEY\s*\([^)]+\)\s*REFERENCES\s+\w+\.\s*`\w+`', ''
    return $Def
}

# Creation order respecting FK
$CreateOrder = @(
    "roles", "permissions", "location", "vehicles", "tour",
    "prices", "itinerary", "tour_departure", "tour_image_ids", "tour_media_lock",
    "departure_meeting_point",
    "booking", "customer", "booking_reservation", "booking_price_snapshot",
    "booking_change", "booking_change_event", "departure_change",
    "payment", "vnpay_transaction", "momo_transaction", "payment_reconciliation", "payment_reconciliation_event",
    "invoice",
    "file_mgmt", "avatar_media_lock", "tour_media_lock",
    "mail_outbox", "mail_template", "mail_delivery_attempt", "mail_suppression",
    "permission_role", "users", "invalidated_token", "user_account_event", "reviews"
)

# Reference tables for seed data
$RefTables = @("roles", "permissions", "location", "vehicles", "prices")

# Generate each schema file
foreach ($db in $Ownership.Keys) {
    $service = $db.Replace("sgu_", "")
    $ownedTables = $Ownership[$db]
    
    $output = @()
    $output += "-- ============================================================"
    $output += "-- Schema: $db"
    $output += "-- Service: $service"
    $output += "-- Generated from: $InputFile"
    $output += "-- Date: $(Get-Date -Format 'yyyy-MM-dd')"
    $output += "-- ============================================================"
    $output += ""
    $output += "SET NAMES utf8mb4;"
    $output += "SET FOREIGN_KEY_CHECKS = 0;"
    $output += ""
    $output += "-- ============================================================"
    $output += "-- DROP TABLES (reverse dependency order)"
    $output += "-- ============================================================"
    
    # Drop infrastructure first
    $output += "DROP TABLE IF EXISTS `{0}_inbox`;" -f $service
    $output += "DROP TABLE IF EXISTS `{0}_outbox`;" -f $service
    
    # Drop extra tables
    if ($ExtraTables.ContainsKey($db)) {
        $extraNames = [regex]::Matches($ExtraTables[$db], 'CREATE TABLE\s+`(\w+)`')
        foreach ($m in $extraNames) {
            $output += "DROP TABLE IF EXISTS `{0}`;" -f $m.Groups[1].Value
        }
    }
    
    # Drop business tables (reverse order for FK)
    $dropOrder = @(
        "booking_change_event", "booking_change", "departure_change",
        "booking_reservation", "customer", "booking_price_snapshot", "booking",
        "payment_reconciliation_event", "payment_reconciliation",
        "momo_transaction", "vnpay_transaction", "payment",
        "invoice",
        "reviews", "user_account_event", "invalidated_token", "permission_role", "users",
        "booking_reservation", "departure_meeting_point", "tour_image_ids", "tour_media_lock",
        "tour_departure", "itinerary", "tour", "prices", "vehicles", "location",
        "mail_outbox", "mail_delivery_attempt", "mail_template", "mail_suppression",
        "file_reference", "avatar_media_lock", "tour_media_lock", "file_mgmt"
    )
    
    # Remove duplicates
    $seen = @{}
    foreach ($t in $dropOrder) {
        if ($ownedTables -contains $t -and -not $seen.ContainsKey($t)) {
            $output += "DROP TABLE IF EXISTS `{0}`;" -f $t
            $seen[$t] = $true
        }
    }
    
    $output += ""
    $output += "-- ============================================================"
    $output += "-- CREATE TABLES - BUSINESS (dependency order)"
    $output += "-- ============================================================"
    
    foreach ($t in $CreateOrder) {
        if ($ownedTables -contains $t -and $AllTables.ContainsKey($t)) {
            $def = Clean-TableDef $AllTables[$t]
            $output += $def.Trim()
            $output += ""
        }
    }
    
    # Extra infrastructure tables
    if ($ExtraTables.ContainsKey($db)) {
        $output += "-- ============================================================"
        $output += "-- INFRASTRUCTURE TABLES"
        $output += "-- ============================================================"
        $output += $ExtraTables[$db]
        $output += ""
    }
    
    # Outbox/Inbox
    $output += (New-OutboxTable -Service $service)
    $output += ""
    $output += (New-InboxTable -Service $service)
    $output += ""
    
    # Seed data for reference tables
    $output += "-- ============================================================"
    $output += "-- SEED DATA (reference tables only)"
    $output += "-- ============================================================"
    foreach ($t in $RefTables) {
        if ($ownedTables -contains $t -and $AllInserts.ContainsKey($t)) {
            foreach ($stmt in $AllInserts[$t]) {
                $output += $stmt
            }
            $output += ""
        }
    }
    
    $output += "SET FOREIGN_KEY_CHECKS = 1;"
    
    $outputPath = Join-Path $OutputDir "${db}.sql"
    $output -join "`n" | Set-Content -Path $outputPath -Encoding UTF8
    Write-Host "Generated: $outputPath ($($ownedTables.Count) business tables)" -ForegroundColor Green
}

Write-Host "`n=== Generation Complete ===" -ForegroundColor Cyan
Write-Host "Output directory: $OutputDir" -ForegroundColor Gray
Get-ChildItem $OutputDir -Filter "*.sql" | Select-Object Name, Length | Format-Table -AutoSize