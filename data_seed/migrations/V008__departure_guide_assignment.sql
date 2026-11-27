CREATE TABLE IF NOT EXISTS departure_guide_assignment (
    departure_id INT PRIMARY KEY,
    guide_account_id INT NOT NULL,
    assigned_by INT NOT NULL,
    assigned_at DATETIME NOT NULL,
    INDEX ix_guide_departures (guide_account_id, departure_id),
    FOREIGN KEY (departure_id) REFERENCES departure_manifest(departure_id)
);
