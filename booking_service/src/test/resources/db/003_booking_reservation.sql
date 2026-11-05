-- TEST FIXTURE ONLY. Loaded by automated tests. Never import into dbtour_v2.
-- Phase C: import after 001_mail_outbox.sql in dbtour_v2, before restarting booking/payment.
-- No existing bookings, seats, paid invoices or seed passwords are changed.
CREATE TABLE IF NOT EXISTS booking_reservation (
    booking_id INT NOT NULL PRIMARY KEY,
    departure_id INT NOT NULL,
    departure_at DATETIME(6) NOT NULL,
    seats INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    reminder_one_at DATETIME(6) NULL,
    reminder_two_at DATETIME(6) NULL,
    cancel_reason VARCHAR(40) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_reservation_booking FOREIGN KEY (booking_id) REFERENCES booking(id),
    CONSTRAINT fk_reservation_departure FOREIGN KEY (departure_id) REFERENCES tour_departure(id),
    CONSTRAINT ck_reservation_seats CHECK (seats > 0),
    CONSTRAINT ck_reservation_status CHECK (status IN ('HELD','CHECKOUT','PAID','EXPIRED','CANCELLED')),
    INDEX ix_reservation_expiry (status,expires_at,departure_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Persist verified successful callbacks that cannot safely fulfil a booking.
-- This is an audit queue, NOT an automatic refund or a paid booking.
CREATE TABLE IF NOT EXISTS payment_reconciliation (
    txn_ref VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    booking_id INT NOT NULL,
    reason VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    provider_paid_at DATETIME(6) NOT NULL,
    amount_vnd BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    INDEX ix_reconciliation_status (status,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
