-- TEST FIXTURE ONLY. Never import into dbtour_v2.
CREATE TABLE IF NOT EXISTS payment_reconciliation_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    txn_ref VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    booking_id INT NOT NULL,
    actor_id BIGINT NOT NULL,
    action VARCHAR(24) NOT NULL,
    amount_vnd BIGINT NOT NULL,
    reference VARCHAR(100) NOT NULL,
    note VARCHAR(1000) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY ux_reconciliation_action_reference (action,reference),
    INDEX ix_reconciliation_event_txn (txn_ref,id),
    INDEX ix_reconciliation_event_actor (actor_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
