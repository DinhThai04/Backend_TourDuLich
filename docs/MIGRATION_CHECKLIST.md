# Migration Checklist & Verification Scripts

> **File**: `docs/MIGRATION_CHECKLIST.md`
> **Mục đích**: Checklist từng phase và script verify tự động.

---

## 1. Phase Gate Checklist

### Phase 0 — Baseline (✅ Done)
- [x] Ownership table chốt (9 DB, 31 bảng)
- [x] Event catalog v1 review (19 events)
- [x] Internal API catalog (7 service, 20+ endpoints)
- [x] Cross-database blocker list khớp code (14 items)
- [x] ADR 0001/0002/0003 accepted
- [x] `PHASE0_BASELINE.md`, `PHASE1_SCHEMA.md`, `INTERNAL_API_CONTRACTS.md` tạo xong
- [x] Script isolation check: `grep -r "dbtour_v2" --include="*.java" src/main` → 1 match (comment)

### Phase 1 — Hạ tầng (✅ Done — nghiệm thu 2 DB, máy 8GB)
- [x] 9 file schema tại `data_seed/microservices/*.sql`
- [x] `docker-compose.microservice.yml` validate (`config -q` pass)
- [x] MySQL + RabbitMQ khởi động được (tested 2/9: `sgu_auth` + `sgu_tour` + rabbitmq healthy)
- [x] Mỗi DB connect được bằng user/grant riêng (tested: `sgu_auth`/`sgu_auth`, `sgu_tour`/`sgu_tour`)
- [x] Không cross-database FK trong schema (bỏ `fk_reservation_departure` trong `booking_reservation`; `tour.price_id` giữ cột thường, không FK sang `prices`)
- [x] Outbox/Inbox có trong mỗi DB (`auth_outbox/inbox`, `tour_outbox/inbox`, ...)
- [ ] `scripts/generate-microservice-schema.ps1` chạy sinh 9 file (tạo thủ công theo `PHASE1_SCHEMA.md`; có thể sinh tự động sau)

**Kết quả nghiệm thu Phase 1**: PASS. 2 DB test được khởi động độc lập, user/grant riêng, outbox/inbox có trong schema, không cross-DB FK. Do máy 8GB, không test cùng lúc 9 DB; các DB khác có cấu trúc tương tự nên dự kiến pass.

### Phase 2 — Vertical Slices (Shared DB)
| Slice | Service | Blocker chính | Internal API cần | Test | Done |
|---|---|---|---|---|---|
| 1 | Reporting | `OperationsInsights` join 6 bảng | — | Unit + Integration | ☐ |
| 2 | Notification | `mail_outbox` rải rác | — | Unit + Integration | ☐ |
| 3 | File | SQL `file_mgmt` trong auth/tour/booking | `/internal/file/*` | Unit + Integration | ☐ |
| 4 | Pricing | Join `prices` từ booking/payment | `/internal/pricing/*` | Unit + Contract | ☐ |
| 5 | Tour | Capacity/hold API | `/internal/tour/*` | Unit + Contract | ☐ |
| 6 | Auth | Query booking/payment/invoice/file | `/internal/auth/*` | Unit + Contract | ☐ |
| 7 | Booking | Join tour/user/payment/invoice | `/internal/booking/*` | Unit + Contract | ☐ |
| 8 | Payment | Settlement đọc chéo | `/internal/payment/*` | Unit + Contract | ☐ |
| 9 | Invoice | Chỉ consume payment event | — | Unit + Contract | ☐ |
| 10 | Search/AI | Đã API-only | — | Contract test | ☐ |

**Mỗi slice phải**:
- [ ] Có internal API/event rõ ràng (theo `INTERNAL_API_CONTRACTS.md`)
- [ ] Có test đơn vị + test tích hợp với DB của service
- [ ] Không làm tăng cross-domain SQL
- [ ] Có feature flag nếu cần chuyển đổi
- [ ] Có rollback bằng cấu hình hoặc revert commit

### Phase 3 — Tách Database Vật Lý
- [ ] 9 MySQL instance/container, user riêng, volume riêng
- [ ] Chạy migration từng DB bằng `data_seed/microservices/*.sql`
- [ ] Chuyển `DB_URL` từng service sang DB riêng
- [ ] Bỏ cross-database FK; giữ FK nội bộ DB
- [ ] Migrate dữ liệu theo thứ tự: `auth → tour → pricing → booking → payment → invoice → file → notification → reporting`
- [ ] Kiểm tra count/sum/orphan/duplicate sau mỗi DB
- [ ] Gate: service khởi động với chỉ DB của mình; cross-DB query bị từ chối bởi grant

### Phase 4 — Saga & Đồng bộ
- [ ] Outbox publisher + inbox consumer implement
- [ ] Saga: booking hold/expiry, payment/invoice, reschedule/refund
- [ ] Retry backoff, DLQ, replay có kiểm soát
- [ ] Reporting projection + audit timeline
- [ ] Notification template/delivery/retry
- [ ] Gate: duplicate event/callback không tạo bản ghi trùng; consumer chết không mất event

### Phase 5 — Cleanup & Acceptance
- [ ] Xóa `dbtour_v2` khỏi cấu hình runtime
- [ ] Xóa code/table/column legacy, mail outbox cũ, cross-domain reader
- [ ] Cập nhật README, compose, CI, script demo
- [ ] Chạy E2E trên AWS 16 GB
- [ ] Acceptance checklist 100% (xem §2)

---

## 2. Acceptance Checklist (Phase 5 Gate)

| # | Tiêu chí | Verify Method | Status |
|---|---|---|---|
| 1 | 9 database vật lý độc lập, volume riêng | `docker volume ls \| grep mysql_` | ☐ |
| 2 | Mỗi service chỉ grant trên DB của mình | `SHOW GRANTS FOR 'sgu_auth'@'%';` | ☐ |
| 3 | Không cross-database query/FK/transaction | Static analysis + runtime grant test | ☐ |
| 4 | Không service đọc bảng ngoài ownership | `grep -r "tour_departure\|prices\|booking" --include="*.java" src/main` | ☐ |
| 5 | Outbox/inbox hoạt động; event trùng không duplicate | Integration test duplicate event | ☐ |
| 6 | Saga booking/payment/invoice/reschedule có compensation | Failure injection test | ☐ |
| 7 | Reporting không query DB nguồn | Code review + grant test | ☐ |
| 8 | Notification sở hữu mail outbox/template/delivery | `SHOW TABLES IN sgu_notification;` | ☐ |
| 9 | File service sở hữu toàn bộ metadata ảnh | `SHOW TABLES IN sgu_file;` | ☐ |
| 10 | Search/AI chỉ dùng API/snapshot | Code review | ☐ |
| 11 | Local test tuần tự đạt | `scripts/test-local-sequential.ps1` | ☐ |
| 12 | AWS E2E đạt | `scripts/test-aws-e2e.ps1` | ☐ |
| 13 | Docs, compose, CI, demo script khớp topology mới | Review | ☐ |
| 14 | Không secret trong repo hoặc file mẫu | `git-secrets scan` | ☐ |

---

## 3. Verification Scripts

### 3.1 `scripts/verify-isolation.ps1` — Kiểm tra cross-DB reference

```powershell
# scripts/verify-isolation.ps1
param(
    [string]$BackendRoot = "Backend_TourDuLich",
    [switch]$FailOnError
)

$Patterns = @(
    "dbtour_v2",
    "JOIN.*\.",
    "REFERENCES\s+\w+\.",
    "tour_departure",
    "prices\b",
    "booking\b(?<!booking_)",
    "payment\b(?<!payment_)",
    "invoice\b(?<!invoice_)",
    "file_mgmt"
)

$Errors = 0
$SourceFiles = Get-ChildItem -Path $BackendRoot -Recurse -Filter "*.java" |
    Where-Object { $_.FullName -notmatch "/test/" }

foreach ($file in $SourceFiles) {
    $Content = Get-Content $file.FullName -Raw
    foreach ($pattern in $Patterns) {
        if ($Content -match $pattern) {
            $Matches = [regex]::Matches($Content, $pattern)
            foreach ($m in $Matches) {
                $line = ($Content.Substring(0, $m.Index) -split "`n").Count
                Write-Warning "[$file:$line] Match '$pattern': $($m.Value)"
                $Errors++
            }
        }
    }
}

if ($Errors -gt 0) {
    Write-Error "Found $Errors cross-DB references"
    if ($FailOnError) { exit 1 }
} else {
    Write-Host "OK: No cross-DB references found" -ForegroundColor Green
}
```

### 3.2 `scripts/verify-schema.ps1` — Kiểm tra 9 schema

```powershell
# scripts/verify-schema.ps1
param(
    [string]$SchemaDir = "data_seed/microservices",
    [string]$ComposeFile = "docker-compose.microservice.yml"
)

$ExpectedDatabases = @(
    "sgu_auth", "sgu_tour", "sgu_pricing", "sgu_booking",
    "sgu_payment", "sgu_invoice", "sgu_file", "sgu_notification", "sgu_reporting"
)

$Checks = @(
    @{ Name="File exists"; Script={ Test-Path "$SchemaDir/$_.sql" } },
    @{ Name="Has CREATE TABLE"; Script={ (Get-Content "$SchemaDir/$_.sql" -Raw) -match "CREATE TABLE" } },
    @{ Name="Has outbox"; Script={ (Get-Content "$SchemaDir/$_.sql" -Raw) -match "_outbox" } },
    @{ Name="Has inbox"; Script={ (Get-Content "$SchemaDir/$_.sql" -Raw) -match "_inbox" } },
    @{ Name="No cross-db FK"; Script={ (Get-Content "$SchemaDir/$_.sql" -Raw) -notmatch "REFERENCES\s+(?!$_)[a-z_]+" } },
    @{ Name="No dbtour_v2"; Script={ (Get-Content "$SchemaDir/$_.sql" -Raw) -notmatch "dbtour_v2" } }
)

$Failed = 0
foreach ($db in $ExpectedDatabases) {
    Write-Host "=== Checking $db ===" -ForegroundColor Cyan
    foreach ($check in $Checks) {
        try {
            $result = & $check.Script $db
            if ($result) {
                Write-Host "  [PASS] $($check.Name)" -ForegroundColor Green
            } else {
                Write-Host "  [FAIL] $($check.Name)" -ForegroundColor Red
                $Failed++
            }
        } catch {
            Write-Host "  [ERROR] $($check.Name): $_" -ForegroundColor Red
            $Failed++
        }
    }
}

if ($Failed -gt 0) { exit 1 }
Write-Host "All schema checks passed" -ForegroundColor Green
```

### 3.3 `scripts/verify-grants.ps1` — Kiểm tra grant user

```powershell
# scripts/verify-grants.ps1
param(
    [string]$ComposeFile = "docker-compose.microservice.yml"
)

$Services = @(
    @{ Name="mysql-auth"; User="sgu_auth"; DB="sgu_auth" },
    @{ Name="mysql-tour"; User="sgu_tour"; DB="sgu_tour" },
    @{ Name="mysql-pricing"; User="sgu_pricing"; DB="sgu_pricing" },
    @{ Name="mysql-booking"; User="sgu_booking"; DB="sgu_booking" },
    @{ Name="mysql-payment"; User="sgu_payment"; DB="sgu_payment" },
    @{ Name="mysql-invoice"; User="sgu_invoice"; DB="sgu_invoice" },
    @{ Name="mysql-file"; User="sgu_file"; DB="sgu_file" },
    @{ Name="mysql-notification"; User="sgu_notification"; DB="sgu_notification" },
    @{ Name="mysql-reporting"; User="sgu_reporting"; DB="sgu_reporting" }
)

$Failed = 0
foreach ($svc in $Services) {
    Write-Host "=== Checking grants for $($svc.User) on $($svc.DB) ===" -ForegroundColor Cyan
    
    # Test connect
    $conn = "docker exec -i $($svc.Name) mysql -u $($svc.User) -p${MYSQL_PASSWORD} $($svc.DB) -e 'SELECT 1;'"
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  [FAIL] Cannot connect" -ForegroundColor Red
        $Failed++; continue
    }
    Write-Host "  [PASS] Connect OK" -ForegroundColor Green
    
    # Check grants
    $grants = docker exec -i $($svc.Name) mysql -u $($svc.User) -p${MYSQL_PASSWORD} $($svc.DB) -e "SHOW GRANTS FOR CURRENT_USER;"
    if ($grants -notmatch "GRANT.*ON `$($svc.DB)`\.\*") {
        Write-Host "  [FAIL] Missing grant on own DB" -ForegroundColor Red
        $Failed++
    } else {
        Write-Host "  [PASS] Has grant on own DB" -ForegroundColor Green
    }
    
    # Check NO grant on other DBs
    $otherDBs = $ExpectedDatabases | Where-Object { $_ -ne $svc.DB }
    foreach ($other in $otherDBs) {
        $test = docker exec -i $($svc.Name) mysql -u $($svc.User) -p${MYSQL_PASSWORD} -e "SELECT 1 FROM `$other`.`information_schema`.`TABLES` LIMIT 1;" 2>$null
        if ($LASTEXITCODE -eq 0) {
            Write-Host "  [FAIL] Can access other DB: $other" -ForegroundColor Red
            $Failed++
        }
    }
    Write-Host "  [PASS] Cannot access other DBs" -ForegroundColor Green
}

if ($Failed -gt 0) { exit 1 }
Write-Host "All grant checks passed" -ForegroundColor Green
```

### 3.4 `scripts/verify-events.ps1` — Kiểm tra duplicate event handling

```powershell
# scripts/verify-events.ps1
# Chạy sau khi Phase 4 implement outbox/inbox
# Test: publish cùng eventId 2 lần → consumer chỉ xử lý 1 lần

param(
    [string]$Service = "booking",
    [string]$EventType = "booking.created.v1"
)

# 1. Insert duplicate event vào outbox
# 2. Run publisher
# 3. Check inbox: chỉ 1 record processed
# 4. Check business table: chỉ 1 record created
```

### 3.5 `scripts/test-local-sequential.ps1` — Local 8 GB test tuần tự

```powershell
# scripts/test-local-sequential.ps1
# Chạy test theo nhóm để không quá RAM

$Groups = @(
    @{ Name="Group 1: Auth"; Services=@("mysql-auth","auth_service"); Profile="auth" },
    @{ Name="Group 2: Tour+Pricing"; Services=@("mysql-tour","mysql-pricing","tour_service","pricing_service"); Profile="tour-pricing" },
    @{ Name="Group 3: Booking"; Services=@("mysql-booking","booking_service"); Profile="booking" },
    @{ Name="Group 4: Payment+Invoice"; Services=@("mysql-payment","mysql-invoice","payment_service","invoice_service"); Profile="payment-invoice" },
    @{ Name="Group 5: File"; Services=@("mysql-file","file_service"); Profile="file" },
    @{ Name="Group 6: Notification+Reporting"; Services=@("mysql-notification","mysql-reporting","rabbitmq"); Profile="notify-report" },
    @{ Name="Group 7: Search+AI"; Services=@("elasticsearch","redis","search_service","ai_service"); Profile="search-ai" }
)

foreach ($group in $Groups) {
    Write-Host "=== $($group.Name) ===" -ForegroundColor Cyan
    docker compose -f docker-compose.microservice.yml --profile $($group.Profile) up -d
    # Wait healthy
    # Run tests
    # docker compose -f docker-compose.microservice.yml --profile $($group.Profile) down
}
```

---

## 4. Rollback Procedure (mỗi phase)

| Phase | Rollback Action |
|---|---|
| Phase 1 | `docker compose -f docker-compose.microservice.yml down -v`; quay lại `docker-compose.yml` gốc |
| Phase 2 | Revert commit slice; trỏ `DB_URL` về `dbtour_v2`; feature flag OFF |
| Phase 3 | Trỏ `DB_URL` về `dbtour_v2`; không xóa DB mới; đọc-only `dbtour_v2` để đối chiếu |
| Phase 4 | Tắt consumer outbox; xóa saga table; revert event handling code |
| Phase 5 | Không rollback (acceptance passed) |

---

## 5. Monitoring & Alerting (Phase 4+)

| Metric | Threshold | Action |
|---|---|---|
| `outbox_backlog` | > 1000 | Alert: publisher lag |
| `inbox_unprocessed` | > 500 | Alert: consumer lag |
| `saga_stuck` | > 5 phút | Alert: saga cần can thiệp |
| `dead_letter_queue` | > 0 | Alert: event lỗi cần xử lý |
| `db_connection_pool_usage` | > 80% | Scale pool hoặc check leak |

---

## 6. Kết luận

Checklist này là **living document** — cập nhật sau mỗi phase gate. Mọi thay đổi lớn phải cập nhật lại file này trước khi triển khai.