-- TEST FIXTURE ONLY. Loaded by automated tests. Never import into dbtour_v2.
-- Apply after 001/002 capacity and existing mail/reservation migrations. Never rewrites existing bookings.
CREATE TABLE IF NOT EXISTS departure_change (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, client_key VARCHAR(36) NOT NULL UNIQUE,
 source_departure_id INT NOT NULL, target_departure_id INT NOT NULL,
 reason_code VARCHAR(40) NOT NULL, reason VARCHAR(1000) NOT NULL, evidence VARCHAR(1000) NOT NULL,
 response_deadline DATETIME(6) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
 created_by INT NOT NULL, created_at DATETIME(6) NOT NULL, completed_at DATETIME(6) NULL,
 INDEX ix_departure_change_source(source_departure_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS booking_change (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, client_key VARCHAR(80) NOT NULL UNIQUE,
 booking_id INT NOT NULL, account_id INT NOT NULL, kind VARCHAR(20) NOT NULL, shop_change_id BIGINT NULL,
 source_departure_id INT NOT NULL, target_departure_id INT NOT NULL,
 source_departure_at DATETIME(6) NOT NULL, source_return_at DATETIME(6) NOT NULL,
 target_departure_at DATETIME(6) NOT NULL, target_return_at DATETIME(6) NOT NULL,
 status VARCHAR(30) NOT NULL, active_key TINYINT NULL DEFAULT 1, version BIGINT NOT NULL DEFAULT 0,
 reason VARCHAR(1000) NOT NULL, requested_by INT NOT NULL, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 consent_source VARCHAR(30) NULL, consent_note VARCHAR(1000) NULL,
 refund_requested BOOLEAN NOT NULL DEFAULT FALSE, applied_at DATETIME(6) NULL,
 refund_amount DECIMAL(19,0) NULL, refund_reference VARCHAR(191) NULL, refund_recorded_by INT NULL, refund_recorded_at DATETIME(6) NULL,
 UNIQUE KEY uq_active_booking_change(booking_id,active_key),
 UNIQUE KEY uq_booking_refund_reference(refund_reference),
 INDEX ix_booking_change_account(account_id,id), INDEX ix_booking_change_shop(shop_change_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS booking_change_event (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, booking_change_id BIGINT NOT NULL, actor_id INT NOT NULL,
 action VARCHAR(40) NOT NULL, note VARCHAR(1000) NOT NULL, created_at DATETIME(6) NOT NULL,
 INDEX ix_booking_change_event(booking_change_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
