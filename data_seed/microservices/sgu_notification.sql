-- ============================================================
-- Schema: sgu_notification
-- Service: notification
-- Generated from: data_seed/database_all.sql
-- Date: 2026-11-20
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================
-- DROP TABLES (reverse dependency order)
-- ============================================================
DROP TABLE IF EXISTS notification_inbox;
DROP TABLE IF EXISTS notification_outbox;
DROP TABLE IF EXISTS notification_dead_letter;
DROP TABLE IF EXISTS mail_template;
DROP TABLE IF EXISTS mail_delivery_attempt;
DROP TABLE IF EXISTS mail_suppression;
DROP TABLE IF EXISTS mail_outbox;

-- ============================================================
-- CREATE TABLES - BUSINESS (dependency order)
-- ============================================================
CREATE TABLE `mail_outbox` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `event_key` varchar(191) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `event_type` varchar(40) NOT NULL,
  `source_service` varchar(40) NOT NULL,
  `booking_id` int(11) DEFAULT NULL,
  `account_id` int(11) DEFAULT NULL,
  `recipient` varchar(254) NOT NULL,
  `payload` longtext NOT NULL,
  `scheduled_at` datetime(6) NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'PENDING',
  `attempt_count` int(11) NOT NULL DEFAULT 0,
  `next_attempt_at` datetime(6) NOT NULL,
  `claim_token` varchar(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `locked_until` datetime(6) DEFAULT NULL,
  `sent_at` datetime(6) DEFAULT NULL,
  `last_error` varchar(500) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_mail_event` (`event_key`),
  KEY `ix_mail_due` (`status`,`next_attempt_at`,`scheduled_at`),
  KEY `ix_mail_lease` (`status`,`locked_until`),
  KEY `ix_mail_booking` (`booking_id`,`event_type`),
  CONSTRAINT `ck_mail_payload` CHECK (json_valid(`payload`)),
  CONSTRAINT `ck_mail_attempt` CHECK (`attempt_count` >= 0),
  CONSTRAINT `ck_mail_status` CHECK (`status` in ('PENDING','CLAIMED','DISPATCHING','SENT','RETRY','FAILED','UNKNOWN','SKIPPED'))
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- INFRASTRUCTURE TABLES
-- ============================================================
-- mail_template
CREATE TABLE `mail_template` (
  `id` VARCHAR(36) NOT NULL,
  `code` VARCHAR(80) NOT NULL,
  `subject` VARCHAR(200) NOT NULL,
  `body_html` TEXT NOT NULL,
  `body_text` TEXT,
  `variables` JSON,
  `created_at` DATETIME(6) NOT NULL,
  `updated_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_template_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Minimal safe defaults keep every subscribed event processable on a clean install.
-- Operators can replace the wording without changing event contracts.
INSERT INTO `mail_template` (`id`,`code`,`subject`,`body_html`,`body_text`,`variables`,`created_at`,`updated_at`) VALUES
('a72c408b-2ba3-44fc-8ac3-54e202efc501','BOOKING_CREATED','Booking received: {{eventId}}','<p>Your booking request was received.</p><p>Reference: {{eventId}}</p>','Your booking request was received. Reference: {{eventId}}',NULL,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)),
('a72c408b-2ba3-44fc-8ac3-54e202efc502','BOOKING_CONFIRMED','Booking confirmed: {{eventId}}','<p>Your booking is confirmed.</p><p>Reference: {{eventId}}</p>','Your booking is confirmed. Reference: {{eventId}}',NULL,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)),
('a72c408b-2ba3-44fc-8ac3-54e202efc503','BOOKING_CANCELLED','Booking cancelled: {{eventId}}','<p>Your booking was cancelled.</p><p>Reference: {{eventId}}</p>','Your booking was cancelled. Reference: {{eventId}}',NULL,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)),
('a72c408b-2ba3-44fc-8ac3-54e202efc504','BOOKING_EXPIRED','Booking expired: {{eventId}}','<p>Your booking hold expired.</p><p>Reference: {{eventId}}</p>','Your booking hold expired. Reference: {{eventId}}',NULL,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)),
('a72c408b-2ba3-44fc-8ac3-54e202efc505','BOOKING_RESCHEDULED','Booking schedule updated: {{eventId}}','<p>Your booking schedule was updated.</p><p>Reference: {{eventId}}</p>','Your booking schedule was updated. Reference: {{eventId}}',NULL,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)),
('a72c408b-2ba3-44fc-8ac3-54e202efc506','BOOKING_CHANGE_REQUESTED','Booking change requested: {{eventId}}','<p>A booking change was requested.</p><p>Reference: {{eventId}}</p>','A booking change was requested. Reference: {{eventId}}',NULL,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)),
('a72c408b-2ba3-44fc-8ac3-54e202efc512','BOOKING_MAIL_REQUESTED','Booking mail requested: {{eventId}}','<p>Xin chào {{customerName}}, đơn hàng <b>{{bookingId}}</b> ({{tourTitle}}) đã được tạo.</p><p>Số lượng khách: {{passengers}}.</p>','Xin chào {{customerName}}, đơn hàng {{bookingId}} ({{tourTitle}}) đã được tạo. Số lượng khách: {{passengers}}.',NULL,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)),
('a72c408b-2ba3-44fc-8ac3-54e202efc507','PAYMENT_CONFIRMED','Payment confirmed: {{eventId}}','<p>Your payment was confirmed.</p><p>Reference: {{eventId}}</p>','Your payment was confirmed. Reference: {{eventId}}',NULL,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)),
('a72c408b-2ba3-44fc-8ac3-54e202efc508','PAYMENT_REFUNDED','Payment refunded: {{eventId}}','<p>Your payment refund was recorded.</p><p>Reference: {{eventId}}</p>','Your payment refund was recorded. Reference: {{eventId}}',NULL,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)),
('a72c408b-2ba3-44fc-8ac3-54e202efc509','TOUR_DEPARTURE_CREATED','Tour departure available: {{eventId}}','<p>A tour departure is available.</p><p>Reference: {{eventId}}</p>','A tour departure is available. Reference: {{eventId}}',NULL,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)),
('a72c408b-2ba3-44fc-8ac3-54e202efc510','TOUR_DEPARTURE_UPDATED','Tour departure updated: {{eventId}}','<p>A tour departure was updated.</p><p>Reference: {{eventId}}</p>','A tour departure was updated. Reference: {{eventId}}',NULL,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)),
('a72c408b-2ba3-44fc-8ac3-54e202efc511','CUSTOM_NOTIFICATION','Notification: {{eventId}}','<p>{{body}}</p><p>Reference: {{eventId}}</p>','{{body}} Reference: {{eventId}}',NULL,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6));

-- mail_delivery_attempt
CREATE TABLE `mail_delivery_attempt` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `outbox_id` BIGINT NOT NULL,
  `attempt_no` INT NOT NULL,
  `status` VARCHAR(20) NOT NULL, -- SENT, FAILED, RETRY
  `error` VARCHAR(1000),
  `sent_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_attempt_outbox` (`outbox_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- mail_suppression
CREATE TABLE `mail_suppression` (
  `email` VARCHAR(200) NOT NULL,
  `reason` VARCHAR(100) NOT NULL, -- BOUNCE, COMPLAINT, UNSUBSCRIBE
  `created_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- Outbox table for notification
CREATE TABLE `notification_outbox` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `event_id` VARCHAR(36) NOT NULL,
  `event_type` VARCHAR(120) NOT NULL,
  `aggregate_type` VARCHAR(80) NOT NULL,
  `aggregate_id` VARCHAR(80) NOT NULL,
  `aggregate_version` INT NOT NULL,
  `correlation_id` VARCHAR(80),
  `causation_id` VARCHAR(80),
  `payload` JSON NOT NULL,
  `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  `attempts` INT NOT NULL DEFAULT 0,
  `available_at` DATETIME(6) NOT NULL,
  `published_at` DATETIME(6) NULL,
  `last_error` VARCHAR(1000),
  `created_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_outbox_event_id` (`event_id`),
  KEY `idx_outbox_status_available` (`status`, `available_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- Inbox table for notification
CREATE TABLE `notification_inbox` (
  `consumer` VARCHAR(120) NOT NULL,
  `event_id` VARCHAR(36) NOT NULL,
  `event_type` VARCHAR(120) NOT NULL,
  `received_at` DATETIME(6) NOT NULL,
  `processed_at` DATETIME(6) NULL,
  PRIMARY KEY (`consumer`, `event_id`),
  KEY `idx_inbox_unprocessed` (`processed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Durable operator-visible failures after bounded event retries.
CREATE TABLE `notification_dead_letter` (
  `message_id` VARCHAR(36) NOT NULL,
  `event_type` VARCHAR(120) NOT NULL,
  `payload` LONGTEXT NOT NULL,
  `headers` JSON,
  `error` VARCHAR(1000) NOT NULL,
  `attempts` INT NOT NULL DEFAULT 0,
  `created_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`message_id`),
  KEY `idx_notification_dlq_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ============================================================
-- SEED DATA (reference tables only)
-- ============================================================
SET FOREIGN_KEY_CHECKS = 1;
