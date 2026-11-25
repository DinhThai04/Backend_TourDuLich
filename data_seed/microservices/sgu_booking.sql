-- ============================================================
-- Schema: sgu_booking
-- Service: booking_service
-- Generated from: data_seed/database_all.sql
-- Date: 2026-11-20
-- Note: `booking_reservation.departure_id` references tour_departure in sgu_tour;
-- cross-DB FK removed; kept as plain column (no FK constraint).
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================
-- DROP TABLES (reverse dependency order)
-- ============================================================
DROP TABLE IF EXISTS booking_inbox;
DROP TABLE IF EXISTS booking_outbox;
DROP TABLE IF EXISTS booking_saga;
DROP TABLE IF EXISTS booking_change_event;
DROP TABLE IF EXISTS booking_change;
DROP TABLE IF EXISTS departure_change;
DROP TABLE IF EXISTS booking_reservation;
DROP TABLE IF EXISTS customer;
DROP TABLE IF EXISTS booking_price_snapshot;
DROP TABLE IF EXISTS booking;

-- ============================================================
-- CREATE TABLES - BUSINESS (dependency order)
-- ============================================================
CREATE TABLE `booking` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `account_id` int(11) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `status` enum('PENDING_PAYMENT','CONFIRMED','CANCELLED','EXPIRED') NOT NULL,
  `tour_departure_id` int(11) NOT NULL,
  `client_key` varchar(80) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_booking_client_key` (`client_key`),
  KEY `ix_booking_account` (`account_id`,`id`),
  KEY `ix_booking_departure` (`tour_departure_id`),
  KEY `ix_booking_report_day` (`created_at`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `customer` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `address` varchar(255) DEFAULT NULL,
  `booking_type` enum('ADULT','CHILD','TODDLER','INFANT') NOT NULL,
  `birthdate` date NOT NULL,
  `full_name` varchar(255) NOT NULL,
  `gender` bit(1) DEFAULT NULL,
  `status` enum('BOOKED','TRAVELING','COMPLETE') NOT NULL,
  `booking_id` int(11) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FK5jbtn85xnytplih1fv7fm8n25` (`booking_id`),
  CONSTRAINT `FK5jbtn85xnytplih1fv7fm8n25` FOREIGN KEY (`booking_id`) REFERENCES `booking` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `booking_price_snapshot` (
  `booking_id` int(11) NOT NULL,
  `price_id` bigint(20) NOT NULL,
  `adult_price` decimal(15,2) NOT NULL,
  `child_price` decimal(15,2) NOT NULL,
  `toddler_price` decimal(15,2) NOT NULL,
  `infant_price` decimal(15,2) NOT NULL,
  `single_supplement_price` decimal(15,2) NOT NULL DEFAULT 0.00,
  `total_amount` decimal(19,0) NOT NULL,
  `currency` varchar(3) NOT NULL DEFAULT 'VND',
  `snapshot_at` datetime(6) NOT NULL,
  PRIMARY KEY (`booking_id`),
  KEY `ix_snapshot_price` (`price_id`),
  CONSTRAINT `fk_snapshot_booking` FOREIGN KEY (`booking_id`) REFERENCES `booking` (`id`) ON DELETE CASCADE
);

CREATE TABLE `booking_change` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `client_key` varchar(80) NOT NULL,
  `booking_id` int(11) NOT NULL,
  `account_id` int(11) NOT NULL,
  `kind` varchar(20) NOT NULL,
  `shop_change_id` bigint(20) DEFAULT NULL,
  `source_departure_id` int(11) NOT NULL,
  `target_departure_id` int(11) NOT NULL,
  `source_departure_at` datetime(6) NOT NULL,
  `source_return_at` datetime(6) NOT NULL,
  `target_departure_at` datetime(6) NOT NULL,
  `target_return_at` datetime(6) NOT NULL,
  `status` varchar(30) NOT NULL,
  `active_key` tinyint(4) DEFAULT 1,
  `version` bigint(20) NOT NULL DEFAULT 0,
  `reason` varchar(1000) NOT NULL,
  `requested_by` int(11) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `consent_source` varchar(30) DEFAULT NULL,
  `consent_note` varchar(1000) DEFAULT NULL,
  `refund_requested` tinyint(1) NOT NULL DEFAULT 0,
  `applied_at` datetime(6) DEFAULT NULL,
  `refund_amount` decimal(19,0) DEFAULT NULL,
  `refund_reference` varchar(191) DEFAULT NULL,
  `refund_recorded_by` int(11) DEFAULT NULL,
  `refund_recorded_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `client_key` (`client_key`),
  UNIQUE KEY `uq_active_booking_change` (`booking_id`,`active_key`),
  UNIQUE KEY `uq_booking_refund_reference` (`refund_reference`),
  KEY `ix_booking_change_account` (`account_id`,`id`),
  KEY `ix_booking_change_shop` (`shop_change_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `booking_change_event` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `booking_change_id` bigint(20) NOT NULL,
  `actor_id` int(11) NOT NULL,
  `action` varchar(40) NOT NULL,
  `note` varchar(1000) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_booking_change_event` (`booking_change_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `departure_change` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `client_key` varchar(36) NOT NULL,
  `source_departure_id` int(11) NOT NULL,
  `target_departure_id` int(11) NOT NULL,
  `reason_code` varchar(40) NOT NULL,
  `reason` varchar(1000) NOT NULL,
  `evidence` varchar(1000) NOT NULL,
  `response_deadline` datetime(6) NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'OPEN',
  `created_by` int(11) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `client_key` (`client_key`),
  KEY `ix_departure_change_source` (`source_departure_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `booking_reservation` (
  `booking_id` int(11) NOT NULL,
  `departure_id` int(11) NOT NULL,
  `departure_at` datetime(6) NOT NULL,
  `seats` int(11) NOT NULL,
  `status` varchar(20) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `reminder_one_at` datetime(6) DEFAULT NULL,
  `reminder_two_at` datetime(6) DEFAULT NULL,
  `cancel_reason` varchar(40) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`booking_id`),
  KEY `fk_reservation_departure` (`departure_id`),
  KEY `ix_reservation_expiry` (`status`,`expires_at`,`departure_id`),
  CONSTRAINT `fk_reservation_booking` FOREIGN KEY (`booking_id`) REFERENCES `booking` (`id`),
  CONSTRAINT `ck_reservation_seats` CHECK (`seats` > 0),
  CONSTRAINT `ck_reservation_status` CHECK (`status` in ('HELD','CHECKOUT','PAID','EXPIRED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- INFRASTRUCTURE TABLES
-- ============================================================
-- booking_saga: saga state for booking hold/reschedule
CREATE TABLE `booking_saga` (
  `id` VARCHAR(36) NOT NULL,
  `saga_type` VARCHAR(80) NOT NULL, -- HOLD, RESCHEDULE, REFUND
  `aggregate_id` VARCHAR(80) NOT NULL,
  `status` VARCHAR(30) NOT NULL, -- STARTED, STEP_COMPLETED, COMPENSATING, COMPLETED, FAILED
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

-- Outbox table for booking
CREATE TABLE `booking_outbox` (
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

-- Inbox table for booking
CREATE TABLE `booking_inbox` (
  `consumer` VARCHAR(120) NOT NULL,
  `event_id` VARCHAR(36) NOT NULL,
  `event_type` VARCHAR(120) NOT NULL,
  `received_at` DATETIME(6) NOT NULL,
  `processed_at` DATETIME(6) NULL,
  PRIMARY KEY (`consumer`, `event_id`),
  KEY `idx_inbox_unprocessed` (`processed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET FOREIGN_KEY_CHECKS = 1;

CREATE TABLE IF NOT EXISTS `operation_audit` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `entity_type` varchar(50) NOT NULL,
  `entity_id` varchar(64) NOT NULL,
  `action` varchar(64) NOT NULL,
  `actor_id` int DEFAULT NULL,
  `reason` text,
  `before_json` longtext,
  `after_json` longtext,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_op_audit_entity` (`entity_type`, `entity_id`),
  KEY `ix_op_audit_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `booking_dead_letter` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `message_id` varchar(100) NOT NULL,
  `event_type` varchar(100) NOT NULL,
  `payload` longtext,
  `error` text,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_booking_dlq_msg` (`message_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
