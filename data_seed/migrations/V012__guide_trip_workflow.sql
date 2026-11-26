ALTER TABLE departure_attendance ADD COLUMN note VARCHAR(500);
CREATE TABLE IF NOT EXISTS departure_guide_lock (guide_id INT PRIMARY KEY);
CREATE TABLE IF NOT EXISTS departure_operation_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, departure_id INT NOT NULL, actor_id INT NOT NULL,
    action VARCHAR(40) NOT NULL, note VARCHAR(1000), created_at DATETIME NOT NULL,
    INDEX ix_departure_operation_event (departure_id,id)
);
CREATE TABLE IF NOT EXISTS departure_trip_report (
    departure_id INT PRIMARY KEY, guide_id INT NOT NULL, status VARCHAR(24) NOT NULL,
    summary VARCHAR(2000) NOT NULL, incident_note VARCHAR(1000), handover_note VARCHAR(1000),
    submitted_at DATETIME, reviewed_by INT, reviewed_at DATETIME, admin_comment VARCHAR(500)
);
CREATE TABLE IF NOT EXISTS departure_itinerary_progress (
    departure_id INT NOT NULL, itinerary_id INT NOT NULL, status VARCHAR(24) NOT NULL,
    actual_time DATETIME, note VARCHAR(500), updated_by INT NOT NULL, updated_at DATETIME NOT NULL,
    PRIMARY KEY (departure_id,itinerary_id)
);
ALTER TABLE customer ADD COLUMN guardian_name VARCHAR(100), ADD COLUMN guardian_phone VARCHAR(11);
ALTER TABLE departure_manifest ADD COLUMN outcome VARCHAR(40);
