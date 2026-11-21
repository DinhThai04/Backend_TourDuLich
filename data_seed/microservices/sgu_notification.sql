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


-- ============================================================
-- SEED DATA (reference tables only)
-- ============================================================
SET FOREIGN_KEY_CHECKS = 1;
