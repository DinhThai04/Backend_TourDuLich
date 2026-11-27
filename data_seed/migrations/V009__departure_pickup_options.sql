ALTER TABLE departure_meeting_point
    MODIFY COLUMN landmark VARCHAR(300),
    MODIFY COLUMN directions_url VARCHAR(1000);

CREATE TABLE IF NOT EXISTS departure_extra_meeting_point (
    departure_id INT NOT NULL,
    stop_order INT NOT NULL,
    address VARCHAR(300) NOT NULL,
    landmark VARCHAR(300),
    arrival_time TIME,
    directions_url VARCHAR(1000),
    PRIMARY KEY (departure_id, stop_order),
    FOREIGN KEY (departure_id) REFERENCES tour_departure(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
