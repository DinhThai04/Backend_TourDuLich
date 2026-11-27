package com.example.booking_service.operations;

import com.example.booking_service.repository.httpclient.AuthInternalClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AdminAuditEvents {
    public static final String EVENT_TYPE = "admin.audit.v1";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AuthInternalClient accounts;
    private final com.example.booking_service.reservation.BookingAccess access;

    public void record(int actorId, String action, String targetType, int targetId, String targetLabel,
                       String summary, String reason, Map<String, ?> before, Map<String, ?> after) {
        var account = accounts.getUserById((long) actorId);
        if (account == null || account.get("username") == null) throw new IllegalStateException("Missing audit actor profile");
        String id = UUID.randomUUID().toString();
        var event = new LinkedHashMap<String, Object>();
        event.put("eventId", id);
        event.put("eventType", EVENT_TYPE);
        event.put("producer", "booking-service");
        event.put("occurredAt", Instant.now().toString());
        event.put("correlationId", java.util.Objects.toString(org.slf4j.MDC.get("requestId"), id));
        event.put("actorId", String.valueOf(actorId));
        event.put("actorType", access.admin() ? "ADMIN" : access.can("TOUR_GUIDE_OPERATE") ? "TOUR_GUIDE" : "STAFF");
        event.put("actorName", Objects.toString(account.get("username"), "Quản trị viên #" + actorId));
        event.put("actorRole", access.admin() ? "SUPER_ADMIN" : account.get("role"));
        event.put("actionGroup", "OPERATIONS");
        event.put("actionCode", action);
        event.put("targetType", targetType);
        event.put("targetId", String.valueOf(targetId));
        event.put("targetLabel", targetLabel);
        event.put("summary", summary);
        event.put("reason", reason);
        event.put("before", before);
        event.put("after", after);
        jdbc.update("""
                INSERT INTO booking_outbox(event_id,event_type,aggregate_type,aggregate_id,
                    aggregate_version,payload,status,attempts,available_at,created_at)
                VALUES (?,?,?,?,1,?,'PENDING',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
                """, id, EVENT_TYPE, targetType, targetId, mapper.valueToTree(event).toString());
    }
}
