package com.example.reporting_service.consumer;

import com.example.reporting_service.entity.OperationAudit;
import com.example.reporting_service.repository.AuditRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ReportingEventProcessor {

    private static final String CONSUMER_ID = "reporting-events-v1";
    private static final ZoneId REPORTING_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final JdbcTemplate jdbc;
    private final AuditRepository auditRepository;
    private final ObjectMapper objectMapper;

    public ReportingEventProcessor(JdbcTemplate jdbc, AuditRepository auditRepository, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.auditRepository = auditRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Persists the inbox receipt and audit row in one reporting-database transaction.
     * The Rabbit listener acknowledges only after this method returns (and commits).
     */
    @Transactional
    public boolean process(Message message) {
        MessageProperties properties = message.getMessageProperties();
        JsonNode root = readBody(message);

        String eventId = firstNonBlank(properties.getMessageId(), text(root, "eventId"));
        String eventType = firstNonBlank(properties.getType(), text(root, "eventType"));
        if (eventId == null || eventId.length() > 36) {
            throw new IllegalArgumentException("Event id is missing or exceeds 36 characters");
        }
        if (eventType == null || eventType.length() > 120) {
            throw new IllegalArgumentException("Event type is missing or exceeds 120 characters");
        }
        String bodyEventId = text(root, "eventId");
        String bodyEventType = text(root, "eventType");
        if (bodyEventId != null && !eventId.equals(bodyEventId)) {
            throw new IllegalArgumentException("Message id does not match event id");
        }
        if (bodyEventType != null && !eventType.equals(bodyEventType)) {
            throw new IllegalArgumentException("Message type does not match event type");
        }

        LocalDateTime now = LocalDateTime.now(REPORTING_ZONE);
        int inserted = jdbc.update("""
                INSERT IGNORE INTO reporting_inbox(consumer,event_id,event_type,received_at)
                VALUES (?,?,?,?)
                """, CONSUMER_ID, eventId, eventType, now);
        if (inserted == 0) {
            LocalDateTime processedAt = jdbc.queryForObject("""
                    SELECT processed_at FROM reporting_inbox WHERE consumer=? AND event_id=?
                    """, LocalDateTime.class, CONSUMER_ID, eventId);
            if (processedAt != null) {
                return false;
            }
            throw new IllegalStateException("An existing reporting inbox row is not complete");
        }

        EventMetadata metadata = metadata(root, properties, eventId, eventType);
        OperationAudit audit = OperationAudit.builder()
                .actorId(metadata.producer())
                .action(eventType)
                .entityType(metadata.aggregateType())
                .entityId(metadata.aggregateId())
                .detail(safeDetail(metadata))
                .correlationId(metadata.correlationId())
                .causationId(metadata.causationId())
                .occurredAt(metadata.occurredAt())
                .build();
        if ("admin.audit.v1".equals(eventType)) {
            JsonNode fields = metadata.payload();
            audit.setActorId(text(fields, "actorId"));
            audit.setActorType(text(fields, "actorType"));
            audit.setActorName(text(fields, "actorName"));
            audit.setActorRole(text(fields, "actorRole"));
            audit.setAction(text(fields, "actionCode"));
            audit.setActionGroup(text(fields, "actionGroup"));
            audit.setEntityType(text(fields, "targetType"));
            audit.setEntityId(text(fields, "targetId"));
            audit.setTargetLabel(text(fields, "targetLabel"));
            audit.setSummary(text(fields, "summary"));
            audit.setReason(text(fields, "reason"));
            audit.setBeforeJson(fields.hasNonNull("before") ? fields.get("before").toString() : null);
            audit.setAfterJson(fields.hasNonNull("after") ? fields.get("after").toString() : null);
            audit.setCreatedAt(metadata.occurredAt().atZone(REPORTING_ZONE)
                    .withZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime());
        } else {
            audit.setActorType("SYSTEM");
        }
        auditRepository.save(audit);

        int updated = jdbc.update("""
                UPDATE reporting_inbox SET processed_at=?
                WHERE consumer=? AND event_id=? AND processed_at IS NULL
                """, now, CONSUMER_ID, eventId);
        if (updated != 1) {
            throw new IllegalStateException("Could not complete the reporting inbox receipt");
        }
        return true;
    }

    private JsonNode readBody(Message message) {
        try {
            JsonNode root = objectMapper.readTree(message.getBody());
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("Event body must be a JSON object");
            }
            return root;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Event body is not valid JSON", exception);
        }
    }

    private EventMetadata metadata(JsonNode root, MessageProperties properties, String eventId, String eventType) {
        JsonNode payload = root.path("payload").isObject() ? root.path("payload") : root;
        String producer = firstNonBlank(header(properties, "source-service"), text(root, "producer"), "unknown");
        String aggregateType = firstNonBlank(header(properties, "aggregate_type"), text(root, "aggregateType"), "event");
        String aggregateId = firstNonBlank(header(properties, "aggregate_id"), text(root, "aggregateId"), eventId);
        String correlationId = firstNonBlank(header(properties, "correlation_id"), text(root, "correlationId"));
        String causationId = firstNonBlank(header(properties, "causation_id"), text(root, "causationId"));
        String occurredAtValue = text(root, "occurredAt");
        LocalDateTime occurredAt = parseOccurredAt(occurredAtValue);
        validateLength("producer", producer, 80);
        validateLength("aggregate type", aggregateType, 80);
        validateLength("aggregate id", aggregateId, 80);
        validateLength("correlation id", correlationId, 80);
        validateLength("causation id", causationId, 80);
        return new EventMetadata(eventId, eventType, producer, aggregateType, aggregateId,
                correlationId, causationId, occurredAt, payload);
    }

    private String safeDetail(EventMetadata metadata) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("eventId", metadata.eventId());
        summary.put("eventType", metadata.eventType());
        summary.put("producer", metadata.producer());
        summary.put("aggregateType", metadata.aggregateType());
        summary.put("aggregateId", metadata.aggregateId());
        summary.put("occurredAt", metadata.occurredAt().toString());
        // Keep only operational identifiers; payloads can contain recipient or payment data.
        for (String field : new String[]{"bookingId", "paymentId", "invoiceId", "tourId", "departureId", "refundId"}) {
            JsonNode value = metadata.payload().get(field);
            if (value != null && value.isValueNode()) {
                summary.put(field, value.asText());
            }
        }
        try {
            return objectMapper.writeValueAsString(summary);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize safe event audit detail", exception);
        }
    }

    private LocalDateTime parseOccurredAt(String value) {
        if (value == null || value.isBlank()) {
            return LocalDateTime.now(REPORTING_ZONE);
        }
        try {
            return OffsetDateTime.parse(value).atZoneSameInstant(REPORTING_ZONE).toLocalDateTime();
        } catch (RuntimeException ignored) {
            try {
                return LocalDateTime.parse(value);
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("Event occurredAt is invalid", invalid);
            }
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isValueNode() ? null : value.asText();
    }

    private String header(MessageProperties properties, String name) {
        Object value = properties.getHeaders().get(name);
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private void validateLength(String field, String value, int maximum) {
        if (value != null && value.length() > maximum) {
            throw new IllegalArgumentException("Event " + field + " exceeds " + maximum + " characters");
        }
    }

    private record EventMetadata(String eventId, String eventType, String producer, String aggregateType,
                                 String aggregateId, String correlationId, String causationId,
                                 LocalDateTime occurredAt, JsonNode payload) {
    }
}
