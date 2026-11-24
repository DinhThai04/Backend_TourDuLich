DROP TABLE IF EXISTS reporting_inbox;
DROP TABLE IF EXISTS operation_audit;
DROP TABLE IF EXISTS ops_refund;
DROP TABLE IF EXISTS ops_booking;
DROP TABLE IF EXISTS ops_payment;
DROP TABLE IF EXISTS ops_invoice;
DROP TABLE IF EXISTS ops_tour;
DROP TABLE IF EXISTS ops_user;

CREATE TABLE operation_audit (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  actor_id VARCHAR(80) NOT NULL,
  action VARCHAR(120) NOT NULL,
  entity_type VARCHAR(80) NOT NULL,
  entity_id VARCHAR(80) NOT NULL,
  detail JSON,
  correlation_id VARCHAR(80),
  causation_id VARCHAR(80),
  occurred_at DATETIME(6) NOT NULL
);

CREATE TABLE ops_booking (
  booking_id INT NOT NULL PRIMARY KEY,
  account_id BIGINT NOT NULL,
  departure_id INT NOT NULL,
  tour_id INT NOT NULL,
  tour_code VARCHAR(40),
  status VARCHAR(20) NOT NULL,
  seats INT NOT NULL,
  amount BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  confirmed_at DATETIME(6),
  cancelled_at DATETIME(6),
  last_updated DATETIME(6) NOT NULL,
  refund_amount BIGINT,
  refunded_at DATETIME(6)
);

CREATE TABLE ops_payment (
  payment_id INT NOT NULL PRIMARY KEY,
  booking_id INT NOT NULL,
  account_id BIGINT NOT NULL,
  provider VARCHAR(20) NOT NULL,
  status VARCHAR(20) NOT NULL,
  amount BIGINT NOT NULL,
  txn_ref VARCHAR(80),
  paid_at DATETIME(6),
  created_at DATETIME(6) NOT NULL,
  refund_amount BIGINT,
  refunded_at DATETIME(6)
);

CREATE TABLE ops_refund (
  refund_id VARCHAR(36) NOT NULL PRIMARY KEY,
  booking_id INT NOT NULL,
  source VARCHAR(20) NOT NULL,
  amount BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL,
  refunded_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL
);

CREATE TABLE ops_invoice (
  invoice_id INT NOT NULL PRIMARY KEY,
  booking_id INT NOT NULL,
  account_id BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL,
  amount BIGINT NOT NULL,
  issued_at DATETIME(6) NOT NULL,
  paid_at DATETIME(6)
);

CREATE TABLE ops_tour (
  tour_id INT NOT NULL,
  tour_code VARCHAR(40),
  departure_id INT NOT NULL,
  departure_date DATETIME(6) NOT NULL,
  capacity INT,
  available_seats INT,
  operational_status VARCHAR(20),
  booked_seats INT DEFAULT 0,
  held_seats INT DEFAULT 0,
  last_updated DATETIME(6) NOT NULL,
  PRIMARY KEY (tour_id, departure_id)
);

CREATE TABLE ops_user (
  user_id BIGINT NOT NULL PRIMARY KEY,
  email VARCHAR(200),
  status VARCHAR(20) NOT NULL,
  role VARCHAR(80),
  total_bookings INT DEFAULT 0,
  total_spent BIGINT DEFAULT 0,
  last_active DATETIME(6)
);

CREATE TABLE reporting_inbox (
  consumer VARCHAR(120) NOT NULL,
  event_id VARCHAR(36) NOT NULL,
  event_type VARCHAR(120) NOT NULL,
  received_at TIMESTAMP NOT NULL,
  processed_at TIMESTAMP,
  PRIMARY KEY (consumer, event_id)
);
