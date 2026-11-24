# Phase 1 — Schema 9 Database từ `database_all.sql`

> **Mục đích**: Chia `database_all.sql` thành 9 file `.sql` cho `data_seed/microservices/`
> **Input**: `data_seed/database_all.sql` (31 bảng)
> **Output**: 9 file schema tại `data_seed/microservices/`

---

## 1. Quy tắc tách

1. **Chỉ giữ bảng thuộc ownership** (xem `PHASE0_BASELINE.md` §1)
2. **Bỏ hoàn toàn**: cross-database FK, JOIN, VIEW, TRIGGER tham chiếu bảng ngoài ownership
3. **Thêm**: outbox, inbox, infrastructure tables cho từng DB
4. **Giữ nguyên**: tên bảng, cột, type, index, PK, FK **nội bộ** DB
5. **Auto-increment**: giữ nguyên `AUTO_INCREMENT` value từ seed

---

## 2. Danh sách 9 file output

| File | Database | Bảng chính | Bảng infrastructure mới |
|---|---|---|---|
| `sgu_auth.sql` | `sgu_auth` | `users`, `roles`, `permissions`, `permission_role`, `invalidated_token`, `user_account_event`, `reviews` | `auth_outbox`, `auth_inbox` |
| `sgu_tour.sql` | `sgu_tour` | `tour`, `prices`, `itinerary`, `location`, `vehicles`, `tour_departure`, `tour_image_ids`, `tour_media_lock`, `departure_hold` | `tour_outbox`, `tour_inbox` |
| `sgu_pricing.sql` | `sgu_pricing` | `prices` | `pricing_outbox`, `pricing_inbox` |
| `sgu_booking.sql` | `sgu_booking` | `booking`, `customer`, `booking_reservation`, `booking_price_snapshot`, `booking_change`, `booking_change_event`, `departure_change` | `booking_outbox`, `booking_inbox`, `booking_saga` |
| `sgu_payment.sql` | `sgu_payment` | `payment`, `vnpay_transaction`, `momo_transaction`, `payment_reconciliation`, `payment_reconciliation_event` | `payment_outbox`, `payment_inbox`, `payment_saga` |
| `sgu_invoice.sql` | `sgu_invoice` | `invoice` | `invoice_outbox`, `invoice_inbox` |
| `sgu_file.sql` | `sgu_file` | `file_mgmt`, `avatar_media_lock`, `tour_media_lock`, `file_reference` | `file_outbox`, `file_inbox` |
| `sgu_notification.sql` | `sgu_notification` | `mail_outbox`, `mail_template`, `mail_delivery_attempt`, `mail_suppression` | `notification_outbox`, `notification_inbox` |
| `sgu_reporting.sql` | `sgu_reporting` | `operation_audit`, `ops_booking`, `ops_payment`, `ops_invoice`, `ops_tour`, `ops_user` | `reporting_outbox`, `reporting_inbox` |

---

## 3. Template chung cho từng file

```sql
-- ============================================================
-- Schema: <database_name>
-- Service: <service_name>
-- Generated from: database_all.sql
-- Date: 2026-11-20
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================
-- DROP TABLES (để chạy lại được trên DB trống)
-- ============================================================
DROP TABLE IF EXISTS `<service>_inbox`;
DROP TABLE IF EXISTS `<service>_outbox`;
DROP TABLE IF EXISTS <bảng nghiệp vụ theo thứ tự phụ thuộc ngược>;
-- ... (drop infrastructure tables nếu có)

-- ============================================================
-- CREATE TABLES - NGHIỆP VỤ (theo thứ tự phụ thuộc)
-- ============================================================
-- Copy từ database_all.sql, chỉ giữ bảng thuộc ownership
-- Bỏ: FK tham chiếu bảng ngoài ownership
-- Giữ: PK, FK nội bộ, INDEX, UNIQUE, CHECK

-- ============================================================
-- CREATE TABLES - INFRASTRUCTURE
-- ============================================================

-- Outbox
CREATE TABLE `<service>_outbox` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `event_id` VARCHAR(36) NOT NULL,
  `event_type` VARCHAR(120) NOT NULL,
  `aggregate_type` VARCHAR(80) NOT NULL,
  `aggregate_id` VARCHAR(80) NOT NULL,
  `aggregate_version` INT NOT NULL,
  `correlation_id` VARCHAR(80),
  `causation_id` VARCHAR(80),
  `payload` JSON NOT NULL,
  `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  `attempts` INT NOT NULL DEFAULT 0,
  `available_at` DATETIME(6) NOT NULL,
  `published_at` DATETIME(6) NULL,
  `last_error` VARCHAR(1000),
  `created_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_outbox_event_id` (`event_id`),
  KEY `idx_outbox_status_available` (`status`, `available_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Inbox
CREATE TABLE `<service>_inbox` (
  `consumer` VARCHAR(120) NOT NULL,
  `event_id` VARCHAR(36) NOT NULL,
  `event_type` VARCHAR(120) NOT NULL,
  `received_at` DATETIME(6) NOT NULL,
  `processed_at` DATETIME(6) NULL,
  PRIMARY KEY (`consumer`, `event_id`),
  KEY `idx_inbox_unprocessed` (`processed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- SEED DATA (chỉ bảng reference: roles, permissions, location, vehicles, etc.)
-- ============================================================
-- Copy INSERT từ database_all.sql cho các bảng reference
-- Bảng transaction (booking, payment, invoice, ...) KHÔNG seed ở đây

-- ============================================================
-- GRANT
-- ============================================================
-- GRANT SELECT, INSERT, UPDATE, DELETE ON `<database>`.* TO '<service>'@'%';
-- GRANT SELECT, INSERT, UPDATE, DELETE ON `<database>`.`<service>_outbox` TO '<service>'@'%';
-- GRANT SELECT, INSERT, UPDATE ON `<database>`.`<service>_inbox` TO '<service>'@'%';

SET FOREIGN_KEY_CHECKS = 1;
```

---

## 4. Chi tiết từng file (bảng cần giữ/bỏ)

### 4.1 `sgu_auth.sql`

**Giữ từ `database_all.sql`**:
- `roles` (PK, UNIQUE name)
- `permissions` (PK, UNIQUE code)
- `permission_role` (PK composite, FK → roles, permissions)
- `users` (PK, FK → roles, UNIQUE email, UNIQUE google_sub)
- `invalidated_token` (PK, INDEX user_id)
- `user_account_event` (PK, INDEX target_user_id, actor_id)
- `reviews` (PK, FK → users, UNIQUE user_id+booking_id)

**Bỏ**: Không có bảng cross-database.

**Thêm**:
```sql
-- departure_hold KHÔNG ở đây (thuộc sgu_tour)
-- file_reference KHÔNG ở đây (thuộc sgu_file)

-- Outbox/Inbox
CREATE TABLE `auth_outbox` (...);
CREATE TABLE `auth_inbox` (...);
```

### 4.2 `sgu_tour.sql`

**Giữ từ `database_all.sql`**:
- `location` (PK)
- `vehicles` (PK)
- `tour` (PK, FK → location(departure/destination), vehicles, prices)
- `prices` (PK, FK → tour) — **LƯU Ý**: `prices` thuộc ownership tour trong source, nhưng Phase 0 định nghĩa `prices` thuộc `sgu_pricing`. **Quyết định**: Di chuyển `prices` sang `sgu_pricing.sql`, tour chỉ giữ FK logic `price_id` (không FK vật lý).
- `itinerary` (PK, FK → tour)
- `tour_departure` (PK, FK → tour, INDEX tour_id, UNIQUE tour_code+departure_date)
- `tour_image_ids` (PK, FK → tour)
- `tour_media_lock` (PK)
- `departure_meeting_point` (PK, FK → tour_departure)

**Thêm**:
```sql
-- departure_hold (bảng mới cho capacity hold)
CREATE TABLE `departure_hold` (
  `hold_id` VARCHAR(36) NOT NULL,
  `departure_id` INT NOT NULL,
  `booking_id` INT NOT NULL,
  `seats` INT NOT NULL,
  `status` VARCHAR(20) NOT NULL, -- HELD, RELEASED, EXPIRED
  `expires_at` DATETIME(6) NOT NULL,
  `version` BIGINT NOT NULL DEFAULT 0,
  `created_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`hold_id`),
  KEY `idx_hold_departure` (`departure_id`),
  KEY `idx_hold_booking` (`booking_id`),
  KEY `idx_hold_expires` (`expires_at`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Outbox/Inbox
CREATE TABLE `tour_outbox` (...);
CREATE TABLE `tour_inbox` (...);
```

### 4.3 `sgu_pricing.sql`

**Giữ từ `database_all.sql`**:
- `prices` (PK, FK logic → tour — **không FK vật lý**)

**Thêm**: Outbox/Inbox

### 4.4 `sgu_booking.sql`

**Giữ từ `database_all.sql`**:
- `booking` (PK, INDEX tour_departure_id, INDEX account_id, UNIQUE client_key)
- `customer` (PK, FK → booking)
- `booking_reservation` (PK, FK → booking, FK logic → tour_departure, INDEX departure_id)
- `booking_price_snapshot` (PK, FK → booking)
- `booking_change` (PK, FK → booking, INDEX source_departure_id, target_departure_id)
- `booking_change_event` (PK, FK → booking_change)
- `departure_change` (PK, FK → tour_departure source/target)

**Bỏ**: FK vật lý đến `tour_departure` (giữ cột `tour_departure_id` làm logic reference)

**Thêm**:
```sql
-- booking_saga (cho saga hold/reschedule)
CREATE TABLE `booking_saga` (
  `id` VARCHAR(36) NOT NULL,
  `saga_type` VARCHAR(80) NOT NULL, -- HOLD, RESCHEDULE, REFUND
  `aggregate_id` VARCHAR(80) NOT NULL,
  `status` VARCHAR(30) NOT NULL, -- STARTED, STEP_COMPLETED, COMPENSATING, COMPLETED, FAILED
  `current_step` VARCHAR(120) NOT NULL,
  `payload` JSON NOT NULL,
  `attempts` INT NOT NULL DEFAULT 0,
  `last_error` VARCHAR(1000),
  `created_at` DATETIME(6) NOT NULL,
  `updated_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_saga_aggregate` (`aggregate_id`),
  KEY `idx_saga_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Outbox/Inbox
CREATE TABLE `booking_outbox` (...);
CREATE TABLE `booking_inbox` (...);
```

### 4.5 `sgu_payment.sql`

**Giữ từ `database_all.sql`**:
- `payment` (PK, FK logic → booking, INDEX booking_id)
- `vnpay_transaction` (PK, FK logic → booking, UNIQUE txn_ref, INDEX booking_id)
- `momo_transaction` (PK, FK logic → booking, UNIQUE order_id, INDEX booking_id)
- `payment_reconciliation` (PK, FK logic → booking, UNIQUE txn_ref, INDEX booking_id, status)
- `payment_reconciliation_event` (PK, FK → payment_reconciliation)

**Thêm**:
```sql
-- payment_saga (cho saga payment/refund)
CREATE TABLE `payment_saga` (
  `id` VARCHAR(36) NOT NULL,
  `saga_type` VARCHAR(80) NOT NULL, -- SETTLEMENT, REFUND
  `aggregate_id` VARCHAR(80) NOT NULL,
  `status` VARCHAR(30) NOT NULL,
  `current_step` VARCHAR(120) NOT NULL,
  `payload` JSON NOT NULL,
  `attempts` INT NOT NULL DEFAULT 0,
  `last_error` VARCHAR(1000),
  `created_at` DATETIME(6) NOT NULL,
  `updated_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_saga_aggregate` (`aggregate_id`),
  KEY `idx_saga_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Outbox/Inbox
CREATE TABLE `payment_outbox` (...);
CREATE TABLE `payment_inbox` (...);
```

### 4.6 `sgu_invoice.sql`

**Giữ từ `database_all.sql`**:
- `invoice` (PK, FK logic → booking, UNIQUE booking_id, status)

**Thêm**: Outbox/Inbox

### 4.7 `sgu_file.sql`

**Giữ từ `database_all.sql`**:
- `file_mgmt` (PK, INDEX tour_id, location_id, avatar_owner_id, UNIQUE avatar_owner_id, UNIQUE location_id)
- `avatar_media_lock` (PK)
- `tour_media_lock` (PK)

**Thêm**:
```sql
-- file_reference (thay cho cột logic rải rác)
CREATE TABLE `file_reference` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `file_id` VARCHAR(36) NOT NULL,
  `owner_type` VARCHAR(20) NOT NULL, -- TOUR, AVATAR, BOOKING
  `owner_id` VARCHAR(80) NOT NULL,
  `created_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_file_owner` (`file_id`, `owner_type`, `owner_id`),
  KEY `idx_file_ref_owner` (`owner_type`, `owner_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Outbox/Inbox
CREATE TABLE `file_outbox` (...);
CREATE TABLE `file_inbox` (...);
```

### 4.8 `sgu_notification.sql`

**Giữ từ `database_all.sql`**:
- `mail_outbox` (PK, INDEX status, next_attempt_at, booking_id)

**Thêm**:
```sql
-- mail_template
CREATE TABLE `mail_template` (
  `id` VARCHAR(36) NOT NULL,
  `code` VARCHAR(80) NOT NULL,
  `subject` VARCHAR(200) NOT NULL,
  `body_html` TEXT NOT NULL,
  `body_text` TEXT,
  `variables` JSON,
  `created_at` DATETIME(6) NOT NULL,
  `updated_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_template_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- mail_delivery_attempt
CREATE TABLE `mail_delivery_attempt` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `outbox_id` BIGINT NOT NULL,
  `attempt_no` INT NOT NULL,
  `status` VARCHAR(20) NOT NULL, -- SENT, FAILED, RETRY
  `error` VARCHAR(1000),
  `sent_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_attempt_outbox` (`outbox_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- mail_suppression
CREATE TABLE `mail_suppression` (
  `email` VARCHAR(200) NOT NULL,
  `reason` VARCHAR(100) NOT NULL, -- BOUNCE, COMPLAINT, UNSUBSCRIBE
  `created_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Outbox/Inbox
CREATE TABLE `notification_outbox` (...);
CREATE TABLE `notification_inbox` (...);
```

### 4.9 `sgu_reporting.sql`

**Bảng mới hoàn toàn (read model)**:
```sql
-- operation_audit (timeline thống nhất)
CREATE TABLE `operation_audit` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `actor_id` VARCHAR(80) NOT NULL,
  `action` VARCHAR(120) NOT NULL,
  `entity_type` VARCHAR(80) NOT NULL,
  `entity_id` VARCHAR(80) NOT NULL,
  `detail` JSON,
  `correlation_id` VARCHAR(80),
  `causation_id` VARCHAR(80),
  `occurred_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_audit_actor` (`actor_id`),
  KEY `idx_audit_entity` (`entity_type`, `entity_id`),
  KEY `idx_audit_time` (`occurred_at`),
  KEY `idx_audit_correlation` (`correlation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ops_booking (read model booking cho admin)
CREATE TABLE `ops_booking` (
  `booking_id` INT NOT NULL,
  `account_id` BIGINT NOT NULL,
  `departure_id` INT NOT NULL,
  `tour_id` INT NOT NULL,
  `tour_code` VARCHAR(40),
  `status` VARCHAR(20) NOT NULL,
  `seats` INT NOT NULL,
  `amount` BIGINT NOT NULL,
  `created_at` DATETIME(6) NOT NULL,
  `confirmed_at` DATETIME(6),
  `cancelled_at` DATETIME(6),
  `last_updated` DATETIME(6) NOT NULL,
  PRIMARY KEY (`booking_id`),
  KEY `idx_ops_book_account` (`account_id`),
  KEY `idx_ops_book_departure` (`departure_id`),
  KEY `idx_ops_book_status` (`status`),
  KEY `idx_ops_book_time` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ops_payment, ops_invoice, ops_tour, ops_user (tương tự)
-- ...

-- Outbox/Inbox
CREATE TABLE `reporting_outbox` (...);
CREATE TABLE `reporting_inbox` (...);
```

---

## 5. Script tạo 9 file tự động

```powershell
# scripts/generate-microservice-schema.ps1
# Input: data_seed/database_all.sql
# Output: data_seed/microservices/*.sql

$InputFile = "data_seed/database_all.sql"
$OutputDir = "data_seed/microservices"
$Databases = @(
    @{ Name="sgu_auth"; Service="auth"; Tables=@("roles","permissions","permission_role","users","invalidated_token","user_account_event","reviews") },
    @{ Name="sgu_tour"; Service="tour"; Tables=@("location","vehicles","tour","itinerary","tour_departure","tour_image_ids","tour_media_lock","departure_meeting_point") },
    @{ Name="sgu_pricing"; Service="pricing"; Tables=@("prices") },
    @{ Name="sgu_booking"; Service="booking"; Tables=@("booking","customer","booking_reservation","booking_price_snapshot","booking_change","booking_change_event","departure_change") },
    @{ Name="sgu_payment"; Service="payment"; Tables=@("payment","vnpay_transaction","momo_transaction","payment_reconciliation","payment_reconciliation_event") },
    @{ Name="sgu_invoice"; Service="invoice"; Tables=@("invoice") },
    @{ Name="sgu_file"; Service="file"; Tables=@("file_mgmt","avatar_media_lock","tour_media_lock") },
    @{ Name="sgu_notification"; Service="notification"; Tables=@("mail_outbox") },
    @{ Name="sgu_reporting"; Service="reporting"; Tables=@() } # Chỉ infrastructure + read model
)

# Logic: parse database_all.sql, extract CREATE TABLE/INSERT cho từng bảng
# Thêm infrastructure tables theo template
# Ghi ra $OutputDir/<name>.sql
```

---

## 6. Gate Checklist Phase 1

- [ ] 9 file `.sql` tạo ra tại `data_seed/microservices/`
- [ ] Mỗi file chạy được trên MySQL 8.4 trống (`docker exec -i mysql-auth mysql -u sgu_auth -p sgu_auth < sgu_auth.sql`)
- [ ] Không có FK tham chiếu bảng ngoài ownership
- [ ] Mỗi DB có outbox/inbox
- [ ] `docker compose -f docker-compose.microservice.yml up -d` thành công, 9 DB healthy
- [ ] Kết nối được từng DB bằng user/grant riêng