DROP TABLE IF EXISTS mail_outbox;

CREATE TABLE mail_outbox (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  event_key VARCHAR(191) NOT NULL,
  event_type VARCHAR(40) NOT NULL,
  source_service VARCHAR(40) NOT NULL,
  booking_id INT,
  account_id INT,
  recipient VARCHAR(254) NOT NULL,
  payload LONGTEXT NOT NULL,
  scheduled_at DATETIME(6) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  attempt_count INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(6) NOT NULL,
  claim_token VARCHAR(36),
  locked_until DATETIME(6),
  sent_at DATETIME(6),
  last_error VARCHAR(500),
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT uq_mail_event UNIQUE (event_key)
);
