-- ============================================================
-- Schema: sgu_tour
-- Service: tour_service
-- Generated from: data_seed/database_all.sql
-- Date: 2026-11-20
-- Note: `prices` owned by sgu_pricing; tour.price_id kept as plain column (no cross-DB FK)
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================
-- DROP TABLES (reverse dependency order)
-- ============================================================
DROP TABLE IF EXISTS tour_inbox;
DROP TABLE IF EXISTS tour_outbox;
DROP TABLE IF EXISTS departure_hold;
DROP TABLE IF EXISTS tour_image_ids;
DROP TABLE IF EXISTS tour_media_lock;
DROP TABLE IF EXISTS tour_departure;
DROP TABLE IF EXISTS itinerary;
DROP TABLE IF EXISTS tour;
DROP TABLE IF EXISTS vehicles;
DROP TABLE IF EXISTS location;

-- ============================================================
-- CREATE TABLES - BUSINESS (dependency order)
-- ============================================================
CREATE TABLE `location` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `city` varchar(255) NOT NULL,
  `img` varchar(255) NOT NULL,
  `type` enum('DEPARTURE','DESTINATION') NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `vehicles` (
  `id` varchar(255) NOT NULL,
  `vehicle_type` varchar(255) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tour` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `base_price` decimal(15,2) NOT NULL,
  `description` varchar(255) NOT NULL,
  `duration` int(11) NOT NULL,
  `price_id` bigint(20) NOT NULL,
  `tour_program` varchar(255) NOT NULL,
  `tour_title` varchar(255) NOT NULL,
  `departure_location_id` int(11) NOT NULL,
  `destination_id` int(11) NOT NULL,
  `vehicle_id` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FKsnbvw642wf8axcsq2tvoj3gbq` (`departure_location_id`),
  KEY `FK9y7uqw2ht9716nyos1mcy9p1m` (`destination_id`),
  KEY `FK2uhqnffnhggjhjpp5ycdts5iq` (`vehicle_id`),
  CONSTRAINT `FK2uhqnffnhggjhjpp5ycdts5iq` FOREIGN KEY (`vehicle_id`) REFERENCES `vehicles` (`id`),
  CONSTRAINT `FK9y7uqw2ht9716nyos1mcy9p1m` FOREIGN KEY (`destination_id`) REFERENCES `location` (`id`),
  CONSTRAINT `FKsnbvw642wf8axcsq2tvoj3gbq` FOREIGN KEY (`departure_location_id`) REFERENCES `location` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `itinerary` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `day_number` int(11) NOT NULL,
  `description` mediumtext NOT NULL,
  `meal` varchar(255) NOT NULL,
  `title` varchar(255) NOT NULL,
  `tour_id` int(11) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `ux_itinerary_tour_day` (`tour_id`,`day_number`),
  KEY `FK412aybnynrvmjtt4aknad4q6l` (`tour_id`),
  CONSTRAINT `FK412aybnynrvmjtt4aknad4q6l` FOREIGN KEY (`tour_id`) REFERENCES `tour` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tour_departure` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `available_seats` smallint(6) NOT NULL,
  `departure_date` datetime(6) NOT NULL,
  `return_date` datetime(6) NOT NULL,
  `tour_code` varchar(255) NOT NULL,
  `tour_id` int(11) NOT NULL,
  `capacity` int(11) DEFAULT NULL,
  `version` bigint(20) NOT NULL DEFAULT 0,
  `operational_status` varchar(20) NOT NULL DEFAULT 'OPEN',
  PRIMARY KEY (`id`),
  KEY `FKny8sltvnfchcpyv5bjpfujtx2` (`tour_id`),
  KEY `ix_departure_open` (`tour_id`,`departure_date`,`operational_status`),
  KEY `ix_departure_date` (`departure_date`,`operational_status`),
  CONSTRAINT `FKny8sltvnfchcpyv5bjpfujtx2` FOREIGN KEY (`tour_id`) REFERENCES `tour` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tour_image_ids` (
  `tour_id` int(11) NOT NULL,
  `image_id` varchar(255) DEFAULT NULL,
  KEY `FK7awfdmfgljuwomrdbb2vejtnb` (`tour_id`),
  CONSTRAINT `FK7awfdmfgljuwomrdbb2vejtnb` FOREIGN KEY (`tour_id`) REFERENCES `tour` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tour_media_lock` (
  `tour_id` varchar(9) NOT NULL,
  PRIMARY KEY (`tour_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- INFRASTRUCTURE TABLES
-- ============================================================
-- departure_hold: capacity hold for bookings (owned by tour, used by booking via internal API)
CREATE TABLE `departure_hold` (
  `hold_id` VARCHAR(36) NOT NULL,
  `departure_id` INT NOT NULL,
  `booking_id` INT NOT NULL,
  `seats` INT NOT NULL,
  `status` VARCHAR(20) NOT NULL, -- HELD, RELEASED, EXPIRED
  `expires_at` DATETIME(6) NOT NULL,
  `version` BIGINT NOT NULL DEFAULT 0,
  `created_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`hold_id`),
  KEY `idx_hold_departure` (`departure_id`),
  KEY `idx_hold_booking` (`booking_id`),
  KEY `idx_hold_expires` (`expires_at`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Outbox table for tour
CREATE TABLE `tour_outbox` (
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

-- Inbox table for tour
CREATE TABLE `tour_inbox` (
  `consumer` VARCHAR(120) NOT NULL,
  `event_id` VARCHAR(36) NOT NULL,
  `event_type` VARCHAR(120) NOT NULL,
  `received_at` DATETIME(6) NOT NULL,
  `processed_at` DATETIME(6) NULL,
  PRIMARY KEY (`consumer`, `event_id`),
  KEY `idx_inbox_unprocessed` (`processed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET FOREIGN_KEY_CHECKS = 1;