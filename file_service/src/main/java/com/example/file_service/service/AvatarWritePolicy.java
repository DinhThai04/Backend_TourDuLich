package com.example.file_service.service;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** The direct media endpoint obeys the same target-account policy as profile editing. */
@Component
public class AvatarWritePolicy {
    private final JdbcTemplate jdbc;
    private final String customerRole;

    public AvatarWritePolicy(JdbcTemplate jdbc,
            @Value("${app.registration.customer-role-name:Khách Hàng}") String customerRole) {
        this.jdbc = jdbc;
        this.customerRole = customerRole;
    }

    public void requireWritable(String ownerId, boolean lockTarget) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        long actor;
        try {
            if (authentication == null || !authentication.isAuthenticated()) throw new NumberFormatException();
            actor = Long.parseLong(authentication.getName());
        } catch (RuntimeException invalid) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cần đăng nhập để cập nhật ảnh đại diện");
        }
        var actors = jdbc.queryForList("""
                SELECT u.status,r.name,r.active FROM users u JOIN roles r ON r.id=u.role_id WHERE u.id=?
                """, actor);
        if (actors.isEmpty() || !"ACTIVE".equals(actors.getFirst().get("status"))
                || !active(actors.getFirst().get("active"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tài khoản không hoạt động");
        }
        var targets = jdbc.queryForList("SELECT status,role_id FROM users WHERE id=?"
                + (lockTarget ? " FOR UPDATE" : ""), Long.parseLong(ownerId));
        if (targets.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tài khoản");
        Map<String, Object> target = targets.getFirst();
        if ("ARCHIVED".equals(target.get("status")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Không sửa tài khoản đã lưu trữ");
        if (Long.toString(actor).equals(ownerId)) return;
        if ("admin".equalsIgnoreCase(String.valueOf(actors.getFirst().get("name")))) return;
        boolean mayUpdate = authentication.getAuthorities().stream()
                .anyMatch(authority -> "PERM:USERS_UPDATE".equals(authority.getAuthority()));
        var roles = jdbc.queryForList("SELECT name FROM roles WHERE id=?", target.get("role_id"));
        if (!mayUpdate || roles.isEmpty() || !customerRole.equalsIgnoreCase(String.valueOf(roles.getFirst().get("name"))))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Nhân viên chỉ được cập nhật tài khoản khách hàng");
    }

    private static boolean active(Object value) {
        return Boolean.TRUE.equals(value) || value instanceof Number number && number.intValue() == 1;
    }
}
