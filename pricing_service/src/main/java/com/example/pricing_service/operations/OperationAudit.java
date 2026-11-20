package com.example.pricing_service.operations;

import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class OperationAudit {
    private final JdbcTemplate jdbc;

    public OperationAudit(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String area, long target, String before, String after) {
        if (java.util.Objects.equals(before, after)) return;
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.getName().matches("[0-9]{1,9}")) return;
        jdbc.update("""
                INSERT INTO operation_audit(area,target_id,actor_id,before_value,after_value,created_at)
                VALUES (?,?,?,?,?,?)
                """, area, target, Integer.parseInt(authentication.getName()),
                truncate(before), truncate(after), LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh")));
    }

    private String truncate(String value) {
        return value == null ? null : value.substring(0,
                value.offsetByCodePoints(0, Math.min(2000, value.codePointCount(0, value.length()))));
    }
}
