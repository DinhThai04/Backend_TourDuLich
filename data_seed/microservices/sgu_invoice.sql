-- ============================================================
-- Schema: sgu_invoice
-- Service: invoice
-- Generated from: data_seed/database_all.sql
-- Date: 2026-11-20
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================
-- DROP TABLES (reverse dependency order)
-- ============================================================
DROP TABLE IF EXISTS invoice_inbox;
DROP TABLE IF EXISTS invoice_outbox;
DROP TABLE IF EXISTS invoice;

-- ============================================================
-- CREATE TABLES - BUSINESS (dependency order)
-- ============================================================
CREATE TABLE `invoice` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `account_id` int(11) NOT NULL,
  `booking_id` int(11) NOT NULL,
  `day_of_pay` datetime(6) NOT NULL,
  `pay_method_id` int(11) NOT NULL,
  `status` enum('PAID','PENDING','REFUNDED') NOT NULL,
  `total_booking_tour_expense` decimal(19,0) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_invoice_booking` (`booking_id`),
  KEY `ix_invoice_account` (`account_id`,`id`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Outbox table for invoice
CREATE TABLE `invoice_outbox` (
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


-- Inbox table for invoice
CREATE TABLE `invoice_inbox` (
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

CREATE TABLE IF NOT EXISTS `invoice_dead_letter` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `message_id` varchar(100) NOT NULL,
  `event_type` varchar(100) NOT NULL,
  `payload` longtext,
  `error` text,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_invoice_dlq_msg` (`message_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
