-- Apply to sgu_auth before editing role permissions in an existing deployment.
CREATE TABLE IF NOT EXISTS operation_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    area VARCHAR(40) NOT NULL,
    target_id BIGINT NOT NULL,
    actor_id INT NOT NULL,
    before_value VARCHAR(2000),
    after_value VARCHAR(2000),
    created_at DATETIME(6) NOT NULL,
    INDEX ix_operation_audit_area_id (area, target_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
