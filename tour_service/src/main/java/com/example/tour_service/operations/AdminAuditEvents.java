package com.example.tour_service.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AdminAuditEvents {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final RestClient auth = authClient();

    private static RestClient authClient() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(3000);
        return RestClient.builder().requestFactory(factory).build();
    }

    @Value("${APP_AUTH_BASE_URL:http://auth-service:8080/auth}")
    private String authUrl;
    @Value("${AUTH_INTERNAL_KEY:}")
    private String authKey;

    public record Actor(long id, String name, String role, boolean admin) {}

    public Actor currentActor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || !authentication.getName().matches("[0-9]{1,18}")) return null;
        long actorId = Long.parseLong(authentication.getName());
        Map<String, Object> actor = auth.get().uri(authUrl + "/internal/auth/users/{id}", actorId)
                .header("X-Internal-Auth-Key", authKey).retrieve().body(new ParameterizedTypeReference<>() {});
        if (actor == null || actor.get("username") == null || actor.get("role") == null) {
            throw new IllegalStateException("Missing audit actor profile");
        }
        boolean admin = authentication.getAuthorities().stream()
                .anyMatch(item -> "SGU_SUPER_ADMIN".equals(item.getAuthority()));
        return new Actor(actorId, actor.get("username").toString(), admin ? "SUPER_ADMIN" : actor.get("role").toString(), admin);
    }

    public void record(Actor actor, String code, String group, String targetType, long targetId, String label,
                       String summary, String reason, Map<String, ?> before, Map<String, ?> after) {
        if (actor == null) return;
        String id = UUID.randomUUID().toString();
        var event = new LinkedHashMap<String, Object>();
        event.put("eventId", id);
        event.put("eventType", "admin.audit.v1");
        event.put("producer", "tour-service");
        event.put("occurredAt", Instant.now().toString());
        event.put("correlationId", java.util.Objects.toString(org.slf4j.MDC.get("requestId"), id));
        event.put("actorType", actor.admin() ? "ADMIN" : "STAFF");
        event.put("actorId", String.valueOf(actor.id()));
        event.put("actorName", actor.name());
        event.put("actorRole", actor.role());
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
                INSERT INTO tour_outbox(event_id,event_type,aggregate_type,aggregate_id,
                    aggregate_version,payload,status,attempts,available_at,created_at)
                VALUES (?,'admin.audit.v1',?,?,1,?,'PENDING',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
                """, id, targetType, String.valueOf(targetId), mapper.valueToTree(event).toString());
    }
}
