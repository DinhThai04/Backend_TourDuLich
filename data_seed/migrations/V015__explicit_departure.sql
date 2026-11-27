ALTER TABLE departure_manifest
    ADD COLUMN actual_departure_at DATETIME(6) NULL,
    ADD COLUMN confirmed_by INT NULL;
