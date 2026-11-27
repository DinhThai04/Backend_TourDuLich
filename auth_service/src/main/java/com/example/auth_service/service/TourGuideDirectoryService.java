package com.example.auth_service.service;

import com.example.auth_service.dto.response.TourGuideOption;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TourGuideDirectoryService {
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public List<TourGuideOption> find(String query, Long selectedId) {
        String search = "%" + query.strip().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return jdbc.query("""
                SELECT u.id,u.username,u.email FROM users u JOIN roles r ON r.id=u.role_id
                WHERE u.id=?
                   OR (u.status='ACTIVE' AND r.active=1 AND EXISTS (
                       SELECT 1 FROM permission_role pr JOIN permissions p ON p.id=pr.permission_id
                       WHERE pr.role_id=r.id AND p.code='TOUR_GUIDE_OPERATE'
                   ) AND (u.username LIKE ? ESCAPE '!' OR u.email LIKE ? ESCAPE '!'))
                ORDER BY CASE WHEN u.id=? THEN 0 ELSE 1 END,u.username,u.id LIMIT 30
                """, (rs, index) -> new TourGuideOption(rs.getLong("id"), rs.getString("username"), rs.getString("email")),
                selectedId, search, search, selectedId);
    }
}
