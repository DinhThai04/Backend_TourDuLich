CREATE TABLE IF NOT EXISTS departure_meeting_point (
    departure_id INT PRIMARY KEY,
    address VARCHAR(300) NOT NULL,
    landmark VARCHAR(300),
    arrival_time TIME,
    directions_url VARCHAR(1000),
    CONSTRAINT fk_meeting_departure FOREIGN KEY (departure_id) REFERENCES tour_departure(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS operation_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    area VARCHAR(40) NOT NULL,
    target_id BIGINT NOT NULL,
    actor_id INT NOT NULL,
    before_value VARCHAR(2000),
    after_value VARCHAR(2000),
    created_at DATETIME(6) NOT NULL,
    INDEX ix_operation_audit_area_id (area, target_id, id)
);

SET @sgu_index_sql = IF(
    EXISTS(SELECT 1 FROM information_schema.statistics
           WHERE table_schema=DATABASE() AND table_name='booking' AND index_name='ix_booking_report_day'),
    'DO 0', 'ALTER TABLE booking ADD INDEX ix_booking_report_day (created_at, status)');
PREPARE sgu_index FROM @sgu_index_sql;
EXECUTE sgu_index;
DEALLOCATE PREPARE sgu_index;

SET @sgu_index_sql = IF(
    EXISTS(SELECT 1 FROM information_schema.statistics
           WHERE table_schema=DATABASE() AND table_name='payment' AND index_name='ix_payment_report_day'),
    'DO 0', 'ALTER TABLE payment ADD INDEX ix_payment_report_day (day_of_pay, status)');
PREPARE sgu_index FROM @sgu_index_sql;
EXECUTE sgu_index;
DEALLOCATE PREPARE sgu_index;
SET @sgu_index_sql = NULL;
