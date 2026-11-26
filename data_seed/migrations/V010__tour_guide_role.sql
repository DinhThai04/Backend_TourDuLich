SET NAMES utf8mb4;

INSERT INTO roles (name, active, description)
SELECT 'Hướng dẫn viên', 1, 'Điểm danh trên các chuyến được phân công.'
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE name='Hướng dẫn viên');
