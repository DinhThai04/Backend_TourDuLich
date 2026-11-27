SET NAMES utf8mb4;

INSERT INTO permissions (code, name, api_path, method, module)
SELECT 'TOUR_GUIDE_OPERATE', 'Điểm danh chuyến được phân công', '/api/v1/booking/operations/departures/assigned', 'GET', 'OPERATIONS'
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE code='TOUR_GUIDE_OPERATE');

INSERT INTO permission_role (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code='TOUR_GUIDE_OPERATE'
WHERE r.name IN ('Hướng dẫn viên','tour_guide')
AND NOT EXISTS (SELECT 1 FROM permission_role pr WHERE pr.role_id=r.id AND pr.permission_id=p.id);
