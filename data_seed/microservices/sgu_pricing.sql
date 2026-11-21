-- ============================================================
-- Schema: sgu_pricing
-- Service: pricing
-- Generated from: data_seed/database_all.sql
-- Date: 2026-11-20
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================
-- DROP TABLES (reverse dependency order)
-- ============================================================
DROP TABLE IF EXISTS pricing_inbox;
DROP TABLE IF EXISTS pricing_outbox;
DROP TABLE IF EXISTS prices;

-- ============================================================
-- CREATE TABLES - BUSINESS (dependency order)
-- ============================================================
CREATE TABLE `prices` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `adult_price` decimal(15,2) NOT NULL,
  `child_price` decimal(15,2) NOT NULL,
  `infant_price` decimal(15,2) NOT NULL,
  `single_supplement_price` decimal(15,2) NOT NULL,
  `toddler_price` decimal(15,2) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=16 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Outbox table for pricing
CREATE TABLE `pricing_outbox` (
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


-- Inbox table for pricing
CREATE TABLE `pricing_inbox` (
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
INSERT INTO `prices` (`id`,`adult_price`,`child_price`,`infant_price`,`single_supplement_price`,`toddler_price`) VALUES (1,8490000.00,6390000.00,500000.00,1500000.00,0.00);
INSERT INTO `prices` (`id`,`adult_price`,`child_price`,`infant_price`,`single_supplement_price`,`toddler_price`) VALUES (10,2450000.00,1750000.00,250000.00,950000.00,0.00);
INSERT INTO `prices` (`id`,`adult_price`,`child_price`,`infant_price`,`single_supplement_price`,`toddler_price`) VALUES (11,3550000.00,2485000.00,0.00,700000.00,0.00);
INSERT INTO `prices` (`id`,`adult_price`,`child_price`,`infant_price`,`single_supplement_price`,`toddler_price`) VALUES (12,2050000.00,1537500.00,0.00,300000.00,0.00);
INSERT INTO `prices` (`id`,`adult_price`,`child_price`,`infant_price`,`single_supplement_price`,`toddler_price`) VALUES (13,2150000.00,1075000.00,0.00,500000.00,0.00);
INSERT INTO `prices` (`id`,`adult_price`,`child_price`,`infant_price`,`single_supplement_price`,`toddler_price`) VALUES (14,1350000.00,675000.00,0.00,400000.00,0.00);
INSERT INTO `prices` (`id`,`adult_price`,`child_price`,`infant_price`,`single_supplement_price`,`toddler_price`) VALUES (15,3350000.00,1675000.00,0.00,700000.00,0.00);
INSERT INTO `prices` (`id`,`adult_price`,`child_price`,`infant_price`,`single_supplement_price`,`toddler_price`) VALUES (2,3290000.00,2300000.00,300000.00,800000.00,0.00);
INSERT INTO `prices` (`id`,`adult_price`,`child_price`,`infant_price`,`single_supplement_price`,`toddler_price`) VALUES (3,1100000.00,800000.00,150000.00,0.00,0.00);
INSERT INTO `prices` (`id`,`adult_price`,`child_price`,`infant_price`,`single_supplement_price`,`toddler_price`) VALUES (4,12490000.00,9350000.00,800000.00,2100000.00,0.00);
INSERT INTO `prices` (`id`,`adult_price`,`child_price`,`infant_price`,`single_supplement_price`,`toddler_price`) VALUES (5,4990000.00,3500000.00,400000.00,950000.00,0.00);
INSERT INTO `prices` (`id`,`adult_price`,`child_price`,`infant_price`,`single_supplement_price`,`toddler_price`) VALUES (6,2890000.00,2050000.00,300000.00,650000.00,0.00);
INSERT INTO `prices` (`id`,`adult_price`,`child_price`,`infant_price`,`single_supplement_price`,`toddler_price`) VALUES (7,1150000.00,850000.00,150000.00,0.00,0.00);
INSERT INTO `prices` (`id`,`adult_price`,`child_price`,`infant_price`,`single_supplement_price`,`toddler_price`) VALUES (8,5490000.00,4150000.00,500000.00,1350000.00,0.00);
INSERT INTO `prices` (`id`,`adult_price`,`child_price`,`infant_price`,`single_supplement_price`,`toddler_price`) VALUES (9,7290000.00,5490000.00,600000.00,1600000.00,0.00);

SET FOREIGN_KEY_CHECKS = 1;
