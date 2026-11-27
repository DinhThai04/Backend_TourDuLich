ALTER TABLE vehicles ADD COLUMN capacity INT NULL, ADD CHECK (capacity > 0);
UPDATE vehicles SET capacity=29 WHERE id='VH-OTO-29';
UPDATE vehicles SET capacity=45 WHERE id='VH-OTO-45';

ALTER TABLE tour_departure ADD COLUMN operating_vehicle_id VARCHAR(255) NULL,
    ADD CONSTRAINT fk_departure_operating_vehicle FOREIGN KEY (operating_vehicle_id) REFERENCES vehicles(id);
ALTER TABLE departure_meeting_point ADD COLUMN revision_key CHAR(36) NULL;
ALTER TABLE departure_extra_meeting_point ADD COLUMN revision_key CHAR(36) NULL;
