-- TEST FIXTURE ONLY. Loaded by automated tests. Never import into dbtour_v2.
-- Phase B, MySQL 8 / MariaDB 10.4. Select dbtour_v2 before importing.
-- Only mail infrastructure. No seed, reminders, seats or booking changes.
CREATE TABLE IF NOT EXISTS mail_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_key VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    source_service VARCHAR(40) NOT NULL,
    booking_id INT NULL,
    account_id INT NULL,
    recipient VARCHAR(254) NOT NULL,
    payload LONGTEXT NOT NULL,
    scheduled_at DATETIME(6) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    claim_token VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    locked_until DATETIME(6) NULL,
    sent_at DATETIME(6) NULL,
    last_error VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uq_mail_event UNIQUE (event_key),
    CONSTRAINT ck_mail_payload CHECK (JSON_VALID(payload)),
    CONSTRAINT ck_mail_attempt CHECK (attempt_count >= 0),
    CONSTRAINT ck_mail_status CHECK (status IN
      ('PENDING','CLAIMED','DISPATCHING','SENT','RETRY','FAILED','UNKNOWN','SKIPPED')),
    INDEX ix_mail_due (status, next_attempt_at, scheduled_at),
    INDEX ix_mail_lease (status, locked_until),
    INDEX ix_mail_booking (booking_id, event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
