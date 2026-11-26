package com.example.auth_service.controller;

import com.example.auth_service.dto.request.ApiResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.List;

@RestController
public class TourGuideDirectoryController {
    public record Guide(long id, String name, String email) {}
    private final JdbcTemplate jdbc;

    public TourGuideDirectoryController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/users/tour-guides")
    @PreAuthorize("hasAuthority('SGU_SUPER_ADMIN')")
    public ApiResponse<List<Guide>> guides(@RequestParam(defaultValue = "") String query,
            @RequestParam(required = false) Long selectedId) {
        if (query.length() > 100 || selectedId != null && selectedId < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tham số tra cứu không hợp lệ.");
        }
        String search = "%" + query.strip().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        var rows = jdbc.query("""
                SELECT u.id,u.username,u.email FROM users u JOIN roles r ON r.id=u.role_id
                WHERE u.id=?
                   OR (u.status='ACTIVE' AND r.active=1 AND EXISTS (
                       SELECT 1 FROM permission_role pr JOIN permissions p ON p.id=pr.permission_id
                       WHERE pr.role_id=r.id AND p.code='TOUR_GUIDE_OPERATE'
                   ) AND (u.username LIKE ? ESCAPE '!' OR u.email LIKE ? ESCAPE '!'))
                ORDER BY CASE WHEN u.id=? THEN 0 ELSE 1 END,u.username,u.id LIMIT 30
                """, (rs, index) -> new Guide(rs.getLong("id"), rs.getString("username"), rs.getString("email")),
                selectedId, search, search, selectedId);
        return ApiResponse.<List<Guide>>builder().result(rows).build();
    }
}
