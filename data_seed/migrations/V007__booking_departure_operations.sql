CREATE TABLE IF NOT EXISTS booking_pickup_snapshot (
    booking_id INT NOT NULL PRIMARY KEY,
    departure_id INT NOT NULL,
    address VARCHAR(300) NOT NULL,
    landmark VARCHAR(300),
    arrival_time TIME,
    directions_url VARCHAR(1000),
    confirmed_at DATETIME NOT NULL,
    FOREIGN KEY (booking_id) REFERENCES booking(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS departure_manifest (
    departure_id INT NOT NULL PRIMARY KEY,
    completed_at DATETIME,
    completed_by INT,
    closeout_note VARCHAR(500)
);

CREATE TABLE IF NOT EXISTS departure_attendance (
    departure_id INT NOT NULL,
    customer_id INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    updated_at DATETIME NOT NULL,
    updated_by INT NOT NULL,
    PRIMARY KEY (departure_id, customer_id),
    FOREIGN KEY (departure_id) REFERENCES departure_manifest(departure_id),
    FOREIGN KEY (customer_id) REFERENCES customer(id)
);
