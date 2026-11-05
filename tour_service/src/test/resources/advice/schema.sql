DROP TABLE IF EXISTS tour_departure;
DROP TABLE IF EXISTS tour;
DROP TABLE IF EXISTS location;
CREATE TABLE location(id INT PRIMARY KEY,city VARCHAR(255) NOT NULL,type VARCHAR(20) NOT NULL);
CREATE TABLE tour(id INT PRIMARY KEY,tour_program VARCHAR(255),tour_title VARCHAR(255),duration INT,
    price_id BIGINT,departure_location_id INT,destination_id INT);
CREATE TABLE tour_departure(id INT PRIMARY KEY,tour_id INT,departure_date TIMESTAMP,return_date TIMESTAMP,available_seats SMALLINT,operational_status VARCHAR(20) DEFAULT 'OPEN');
