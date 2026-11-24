-- ============================================================
-- Schema: sgu_file
-- Service: file_service
-- Generated from: data_seed/database_all.sql
-- Date: 2026-11-20
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================
-- DROP TABLES (reverse dependency order)
-- ============================================================
DROP TABLE IF EXISTS file_inbox;
DROP TABLE IF EXISTS file_outbox;
DROP TABLE IF EXISTS file_reference;
DROP TABLE IF EXISTS tour_media_lock;
DROP TABLE IF EXISTS avatar_media_lock;
DROP TABLE IF EXISTS file_mgmt;

-- ============================================================
-- CREATE TABLES - BUSINESS (dependency order)
-- ============================================================
CREATE TABLE `avatar_media_lock` (
  `owner_id` varchar(19) NOT NULL,
  `deleted_at` DATETIME(6) NULL,
  `avatar_version` BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`owner_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `file_mgmt` (
  `id` varchar(255) NOT NULL,
  `content_type` varchar(255) DEFAULT NULL,
  `location_id` int(11) DEFAULT NULL,
  `md5_checksum` varchar(255) DEFAULT NULL,
  `owner_id` varchar(255) NOT NULL,
  `path` varchar(255) DEFAULT NULL,
  `size` bigint(20) DEFAULT NULL,
  `tour_id` varchar(255) NOT NULL,
  `url` varchar(255) DEFAULT NULL,
  `avatar_owner_id` varchar(19) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `ux_file_avatar_owner` (`avatar_owner_id`),
  UNIQUE KEY `ux_file_location` (`location_id`),
  KEY `ix_file_tour` (`tour_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tour_media_lock` (
  `tour_id` varchar(9) NOT NULL,
  `deleted_at` DATETIME(6) NULL,
  PRIMARY KEY (`tour_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- INFRASTRUCTURE TABLES
-- ============================================================
-- file_reference: reference tracking for file ownership
CREATE TABLE `file_reference` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `file_id` VARCHAR(36) NOT NULL,
  `owner_type` VARCHAR(20) NOT NULL, -- TOUR, AVATAR, BOOKING
  `owner_id` VARCHAR(80) NOT NULL,
  `created_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_file_owner` (`file_id`, `owner_type`, `owner_id`),
  KEY `idx_file_ref_owner` (`owner_type`, `owner_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Outbox table for file
CREATE TABLE `file_outbox` (
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

-- Inbox table for file
CREATE TABLE `file_inbox` (
  `consumer` VARCHAR(120) NOT NULL,
  `event_id` VARCHAR(36) NOT NULL,
  `event_type` VARCHAR(120) NOT NULL,
  `received_at` DATETIME(6) NOT NULL,
  `processed_at` DATETIME(6) NULL,
  PRIMARY KEY (`consumer`, `event_id`),
  KEY `idx_inbox_unprocessed` (`processed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET FOREIGN_KEY_CHECKS = 1;
