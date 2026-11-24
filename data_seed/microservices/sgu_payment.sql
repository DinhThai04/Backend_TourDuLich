-- ============================================================
-- Schema: sgu_payment
-- Service: payment
-- Generated from: data_seed/database_all.sql
-- Date: 2026-11-20
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================
-- DROP TABLES (reverse dependency order)
-- ============================================================
DROP TABLE IF EXISTS payment_inbox;
DROP TABLE IF EXISTS payment_outbox;
DROP TABLE IF EXISTS payment_saga;
DROP TABLE IF EXISTS payment_reconciliation_event;
DROP TABLE IF EXISTS payment_reconciliation;
DROP TABLE IF EXISTS momo_transaction;
DROP TABLE IF EXISTS vnpay_transaction;
DROP TABLE IF EXISTS payment;

-- ============================================================
-- CREATE TABLES - BUSINESS (dependency order)
-- ============================================================
CREATE TABLE `payment` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `method` tinyint(4) DEFAULT NULL,
  `account_id` int(11) NOT NULL,
  `booking_id` int(11) NOT NULL,
  `day_of_pay` datetime(6) NOT NULL,
  `pay_method_id` int(11) NOT NULL,
  `status` enum('PENDING','UNPAID','PAID','REFUNDED') NOT NULL,
  `total_booking_tour_expense` decimal(19,0) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_payment_booking` (`booking_id`,`status`),
  KEY `ix_payment_report_day` (`day_of_pay`,`status`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `vnpay_transaction` (
  `txn_ref` varchar(32) NOT NULL,
  `account_id` int(11) NOT NULL,
  `amount_vnd` bigint(20) NOT NULL,
  `booking_id` int(11) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `status` varchar(16) NOT NULL,
  `transaction_no` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`txn_ref`),
  KEY `idx_vnpay_booking` (`booking_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `momo_transaction` (
  `order_id` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `request_id` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `booking_id` int(11) NOT NULL,
  `account_id` int(11) NOT NULL,
  `amount_vnd` bigint(20) NOT NULL,
  `status` varchar(16) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `trans_id` bigint(20) DEFAULT NULL,
  `result_code` int(11) DEFAULT NULL,
  PRIMARY KEY (`order_id`),
  UNIQUE KEY `ux_momo_request` (`request_id`),
  KEY `ix_momo_booking` (`booking_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `payment_reconciliation` (
  `txn_ref` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `booking_id` int(11) NOT NULL,
  `reason` varchar(100) NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'OPEN',
  `provider_paid_at` datetime(6) NOT NULL,
  `amount_vnd` bigint(20) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`txn_ref`),
  KEY `ix_reconciliation_status` (`status`,`created_at`),
  KEY `ix_reconciliation_booking` (`booking_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `payment_reconciliation_event` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `txn_ref` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `booking_id` int(11) NOT NULL,
  `actor_id` bigint(20) NOT NULL,
  `action` varchar(24) NOT NULL,
  `amount_vnd` bigint(20) NOT NULL,
  `reference` varchar(100) NOT NULL,
  `note` varchar(1000) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `ux_reconciliation_action_reference` (`action`,`reference`),
  KEY `ix_reconciliation_event_txn` (`txn_ref`,`id`),
  KEY `ix_reconciliation_event_actor` (`actor_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- INFRASTRUCTURE TABLES
-- ============================================================
-- payment_saga: saga state for payment/refund
CREATE TABLE `payment_saga` (
  `id` VARCHAR(36) NOT NULL,
  `saga_type` VARCHAR(80) NOT NULL, -- SETTLEMENT, REFUND
  `aggregate_id` VARCHAR(80) NOT NULL,
  `status` VARCHAR(30) NOT NULL,
  `current_step` VARCHAR(120) NOT NULL,
  `payload` JSON NOT NULL,
  `attempts` INT NOT NULL DEFAULT 0,
  `last_error` VARCHAR(1000),
  `created_at` DATETIME(6) NOT NULL,
  `updated_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_saga_aggregate` (`aggregate_id`),
  KEY `idx_saga_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- Outbox table for payment
CREATE TABLE `payment_outbox` (
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


-- Inbox table for payment
CREATE TABLE `payment_inbox` (
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
