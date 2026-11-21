-- ============================================================
-- Schema: sgu_reporting
-- Service: reporting
-- Generated from: data_seed/database_all.sql
-- Date: 2026-11-20
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================
-- DROP TABLES (reverse dependency order)
-- ============================================================
DROP TABLE IF EXISTS reporting_inbox;
DROP TABLE IF EXISTS reporting_outbox;
DROP TABLE IF EXISTS operation_audit;
DROP TABLE IF EXISTS ops_booking;
DROP TABLE IF EXISTS ops_payment;
DROP TABLE IF EXISTS ops_invoice;
DROP TABLE IF EXISTS ops_tour;
DROP TABLE IF EXISTS ops_user;

-- ============================================================
-- CREATE TABLES - BUSINESS (dependency order)
-- ============================================================
-- ============================================================
-- INFRASTRUCTURE TABLES
-- ============================================================
-- operation_audit: unified audit timeline
CREATE TABLE `operation_audit` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `actor_id` VARCHAR(80) NOT NULL,
  `action` VARCHAR(120) NOT NULL,
  `entity_type` VARCHAR(80) NOT NULL,
  `entity_id` VARCHAR(80) NOT NULL,
  `detail` JSON,
  `correlation_id` VARCHAR(80),
  `causation_id` VARCHAR(80),
  `occurred_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_audit_actor` (`actor_id`),
  KEY `idx_audit_entity` (`entity_type`, `entity_id`),
  KEY `idx_audit_time` (`occurred_at`),
  KEY `idx_audit_correlation` (`correlation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ops_booking: read model for admin booking dashboard
CREATE TABLE `ops_booking` (
  `booking_id` INT NOT NULL,
  `account_id` BIGINT NOT NULL,
  `departure_id` INT NOT NULL,
  `tour_id` INT NOT NULL,
  `tour_code` VARCHAR(40),
  `status` VARCHAR(20) NOT NULL,
  `seats` INT NOT NULL,
  `amount` BIGINT NOT NULL,
  `created_at` DATETIME(6) NOT NULL,
  `confirmed_at` DATETIME(6),
  `cancelled_at` DATETIME(6),
  `last_updated` DATETIME(6) NOT NULL,
  PRIMARY KEY (`booking_id`),
  KEY `idx_ops_book_account` (`account_id`),
  KEY `idx_ops_book_departure` (`departure_id`),
  KEY `idx_ops_book_status` (`status`),
  KEY `idx_ops_book_time` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ops_payment
CREATE TABLE `ops_payment` (
  `payment_id` INT NOT NULL,
  `booking_id` INT NOT NULL,
  `account_id` BIGINT NOT NULL,
  `provider` VARCHAR(20) NOT NULL,
  `status` VARCHAR(20) NOT NULL,
  `amount` BIGINT NOT NULL,
  `txn_ref` VARCHAR(80),
  `paid_at` DATETIME(6),
  `created_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`payment_id`),
  KEY `idx_ops_pay_booking` (`booking_id`),
  KEY `idx_ops_pay_account` (`account_id`),
  KEY `idx_ops_pay_status` (`status`),
  KEY `idx_ops_pay_time` (`paid_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ops_invoice
CREATE TABLE `ops_invoice` (
  `invoice_id` INT NOT NULL,
  `booking_id` INT NOT NULL,
  `account_id` BIGINT NOT NULL,
  `status` VARCHAR(20) NOT NULL,
  `amount` BIGINT NOT NULL,
  `issued_at` DATETIME(6) NOT NULL,
  `paid_at` DATETIME(6),
  PRIMARY KEY (`invoice_id`),
  KEY `idx_ops_inv_booking` (`booking_id`),
  KEY `idx_ops_inv_account` (`account_id`),
  KEY `idx_ops_inv_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ops_tour
CREATE TABLE `ops_tour` (
  `tour_id` INT NOT NULL,
  `tour_code` VARCHAR(40),
  `departure_id` INT NOT NULL,
  `departure_date` DATETIME(6) NOT NULL,
  `capacity` INT,
  `available_seats` INT,
  `operational_status` VARCHAR(20),
  `booked_seats` INT DEFAULT 0,
  `held_seats` INT DEFAULT 0,
  `last_updated` DATETIME(6) NOT NULL,
  PRIMARY KEY (`tour_id`, `departure_id`),
  KEY `idx_ops_tour_date` (`departure_date`),
  KEY `idx_ops_tour_status` (`operational_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ops_user
CREATE TABLE `ops_user` (
  `user_id` BIGINT NOT NULL,
  `email` VARCHAR(200),
  `status` VARCHAR(20) NOT NULL,
  `role` VARCHAR(80),
  `total_bookings` INT DEFAULT 0,
  `total_spent` BIGINT DEFAULT 0,
  `last_active` DATETIME(6),
  PRIMARY KEY (`user_id`),
  KEY `idx_ops_user_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- Outbox table for reporting
CREATE TABLE `reporting_outbox` (
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


-- Inbox table for reporting
CREATE TABLE `reporting_inbox` (
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
