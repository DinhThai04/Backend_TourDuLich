-- ============================================================
-- SGU Tour Microservices: Multi-database initialization
-- Tạo 9 database riêng biệt, mỗi service 1 database
-- ============================================================
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================
-- 1. sgu_auth (auth_service)
-- ============================================================
CREATE DATABASE IF NOT EXISTS sgu_auth CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE sgu_auth;
SOURCE /docker-entrypoint-initdb.d/sql/sgu_auth.sql;

-- ============================================================
-- 2. sgu_booking (booking_service)
-- ============================================================
CREATE DATABASE IF NOT EXISTS sgu_booking CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE sgu_booking;
SOURCE /docker-entrypoint-initdb.d/sql/sgu_booking.sql;

-- ============================================================
-- 3. sgu_payment (payment_service)
-- ============================================================
CREATE DATABASE IF NOT EXISTS sgu_payment CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE sgu_payment;
SOURCE /docker-entrypoint-initdb.d/sql/sgu_payment.sql;

-- ============================================================
-- 4. sgu_pricing (pricing_service)
-- ============================================================
CREATE DATABASE IF NOT EXISTS sgu_pricing CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE sgu_pricing;
SOURCE /docker-entrypoint-initdb.d/sql/sgu_pricing.sql;

-- ============================================================
-- 5. sgu_tour (tour_service)
-- ============================================================
CREATE DATABASE IF NOT EXISTS sgu_tour CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE sgu_tour;
SOURCE /docker-entrypoint-initdb.d/sql/sgu_tour.sql;

-- ============================================================
-- 6. sgu_file (file_service)
-- ============================================================
CREATE DATABASE IF NOT EXISTS sgu_file CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE sgu_file;
SOURCE /docker-entrypoint-initdb.d/sql/sgu_file.sql;

-- ============================================================
-- 7. sgu_invoice (invoice_service)
-- ============================================================
CREATE DATABASE IF NOT EXISTS sgu_invoice CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE sgu_invoice;
SOURCE /docker-entrypoint-initdb.d/sql/sgu_invoice.sql;

-- ============================================================
-- 8. sgu_notification (notification_service)
-- ============================================================
CREATE DATABASE IF NOT EXISTS sgu_notification CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE sgu_notification;
SOURCE /docker-entrypoint-initdb.d/sql/sgu_notification.sql;

-- ============================================================
-- 9. sgu_reporting (reporting_service)
-- ============================================================
CREATE DATABASE IF NOT EXISTS sgu_reporting CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE sgu_reporting;
SOURCE /docker-entrypoint-initdb.d/sql/sgu_reporting.sql;

-- ============================================================
-- 10. sgu_search (search_service) - database trống, Elasticsearch làm primary store
-- ============================================================
CREATE DATABASE IF NOT EXISTS sgu_search CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ============================================================
-- 11. sgu_ai (ai_service) - database trống, dùng cho lưu conversation nếu cần
-- ============================================================
CREATE DATABASE IF NOT EXISTS sgu_ai CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- The single MySQL container in docker-compose.yml uses one application user.
-- Physical per-service databases in docker-compose.microservice.yml use their own users.
GRANT ALL PRIVILEGES ON `sgu_auth`.* TO 'sgu_tour'@'%';
GRANT ALL PRIVILEGES ON `sgu_booking`.* TO 'sgu_tour'@'%';
GRANT ALL PRIVILEGES ON `sgu_payment`.* TO 'sgu_tour'@'%';
GRANT ALL PRIVILEGES ON `sgu_pricing`.* TO 'sgu_tour'@'%';
GRANT ALL PRIVILEGES ON `sgu_tour`.* TO 'sgu_tour'@'%';
GRANT ALL PRIVILEGES ON `sgu_file`.* TO 'sgu_tour'@'%';
GRANT ALL PRIVILEGES ON `sgu_invoice`.* TO 'sgu_tour'@'%';
GRANT ALL PRIVILEGES ON `sgu_notification`.* TO 'sgu_tour'@'%';
GRANT ALL PRIVILEGES ON `sgu_reporting`.* TO 'sgu_tour'@'%';
GRANT ALL PRIVILEGES ON `sgu_search`.* TO 'sgu_tour'@'%';
GRANT ALL PRIVILEGES ON `sgu_ai`.* TO 'sgu_tour'@'%';
FLUSH PRIVILEGES;

SET FOREIGN_KEY_CHECKS = 1;
