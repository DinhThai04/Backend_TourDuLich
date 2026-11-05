-- TEST FIXTURE ONLY. Loaded by automated tests. Never import into dbtour_v2.
-- TEST FIXTURE ONLY. Never import into dbtour_v2. Test harness restricts port and schema.
CREATE TABLE users (id INT PRIMARY KEY,username VARCHAR(50),email VARCHAR(254),status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE');
CREATE TABLE prices (id INT PRIMARY KEY,adult_price DECIMAL(15,2),child_price DECIMAL(15,2),toddler_price DECIMAL(15,2),infant_price DECIMAL(15,2),single_supplement_price DECIMAL(15,2));
CREATE TABLE tour (id INT PRIMARY KEY,price_id INT,tour_title VARCHAR(255));
CREATE TABLE tour_departure (id INT AUTO_INCREMENT PRIMARY KEY,tour_id INT,departure_date DATETIME(6),available_seats SMALLINT, capacity INT NULL, operational_status VARCHAR(20) NOT NULL DEFAULT 'OPEN', version BIGINT NOT NULL DEFAULT 0);
CREATE TABLE booking (id INT AUTO_INCREMENT PRIMARY KEY,account_id INT,tour_departure_id INT,status VARCHAR(20),created_at DATETIME(6),client_key VARCHAR(80),UNIQUE KEY uk_booking_client_key (client_key));
CREATE TABLE booking_price_snapshot (booking_id INT PRIMARY KEY,price_id BIGINT NOT NULL,adult_price DECIMAL(15,2) NOT NULL,child_price DECIMAL(15,2) NOT NULL,toddler_price DECIMAL(15,2) NOT NULL,infant_price DECIMAL(15,2) NOT NULL,single_supplement_price DECIMAL(15,2) NOT NULL,total_amount DECIMAL(19,0) NOT NULL,currency VARCHAR(3) NOT NULL,snapshot_at DATETIME(6) NOT NULL,CONSTRAINT fk_snapshot_booking FOREIGN KEY (booking_id) REFERENCES booking(id));
CREATE TABLE customer (id INT AUTO_INCREMENT PRIMARY KEY,booking_id INT,full_name VARCHAR(255),birthdate DATE,gender BOOLEAN,address VARCHAR(255),booking_type VARCHAR(20),status VARCHAR(20));
CREATE TABLE vnpay_transaction (txn_ref VARCHAR(32) PRIMARY KEY,booking_id INT,account_id INT,amount_vnd BIGINT,status VARCHAR(16),created_at DATETIME(6),expires_at DATETIME(6),transaction_no VARCHAR(255));
CREATE TABLE payment (id INT AUTO_INCREMENT PRIMARY KEY,account_id INT,booking_id INT,day_of_pay DATETIME(6),pay_method_id INT,status VARCHAR(20),total_booking_tour_expense DECIMAL(15,2));
CREATE TABLE invoice (id INT AUTO_INCREMENT PRIMARY KEY,account_id INT,booking_id INT,day_of_pay DATETIME(6),pay_method_id INT,status VARCHAR(20),total_booking_tour_expense DECIMAL(15,2));
