package com.example.auth_service.operations;

import com.example.auth_service.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AdminAuditEvents {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final UserRepository users;

    public void record(String code, String group, String targetType, long targetId, String label,
                       String summary, String reason, Map<String, ?> before, Map<String, ?> after) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || !authentication.getName().matches("[0-9]{1,18}")) return;
        long actorId = Long.parseLong(authentication.getName());
        var actor = users.findById(actorId).orElseThrow();
        String actorRole = actor.getRole().getName();
        boolean admin = authentication.getAuthorities().stream().anyMatch(item -> "SGU_SUPER_ADMIN".equals(item.getAuthority()));
        String id = UUID.randomUUID().toString();
        var event = new LinkedHashMap<String, Object>();
        event.put("eventId", id);
        event.put("eventType", "admin.audit.v1");
        event.put("producer", "auth-service");
        event.put("occurredAt", Instant.now().toString());
        event.put("correlationId", java.util.Objects.toString(org.slf4j.MDC.get("requestId"), id));
        event.put("actorType", admin ? "ADMIN" : "STAFF");
        event.put("actorId", String.valueOf(actorId));
        event.put("actorName", actor.getUsername());
        event.put("actorRole", admin ? "SUPER_ADMIN" : actorRole);
        event.put("actionCode", code);
        event.put("actionGroup", group);
        event.put("targetType", targetType);
        event.put("targetId", String.valueOf(targetId));
        event.put("targetLabel", label);
        event.put("summary", summary);
        event.put("reason", reason);
        event.put("before", before);
        event.put("after", after);
        jdbc.update("""
                INSERT INTO auth_outbox(event_id,event_type,aggregate_type,aggregate_id,
                    aggregate_version,payload,status,attempts,available_at,created_at)
                VALUES (?,'admin.audit.v1',?,?,1,?,'PENDING',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
                """, id, targetType, String.valueOf(targetId), mapper.valueToTree(event).toString());
    }
}
