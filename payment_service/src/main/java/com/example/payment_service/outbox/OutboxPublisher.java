package com.example.payment_service.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String EXCHANGE = "sgu.events";
    private static final int BATCH_SIZE = 100;

    @Scheduled(fixedDelayString = "${app.payment.outbox.poll-interval-ms:1000}")
    @Transactional
    public void publishPendingEvents() {
        List<Map<String, Object>> events = jdbc.queryForList("""
            SELECT * FROM payment_outbox 
            WHERE status = 'PENDING' 
            AND available_at <= ?
            ORDER BY created_at
            LIMIT ?
            """, LocalDateTime.now(), BATCH_SIZE);

        for (Map<String, Object> event : events) {
            try {
                publishEvent(event);
                jdbc.update("UPDATE payment_outbox SET status = 'PUBLISHED', published_at = ? WHERE id = ?",
                        LocalDateTime.now(), event.get("id"));
                log.debug("Published event {} to RabbitMQ", event.get("event_id"));
            } catch (Exception e) {
                log.error("Failed to publish event {}: {}", event.get("event_id"), e.getMessage(), e);
                int attempts = ((Number) event.get("attempts")).intValue() + 1;
                if (attempts >= 5) {
                    jdbc.update("UPDATE payment_outbox SET status = 'FAILED', attempts = ?, last_error = ? WHERE id = ?",
                            attempts, e.getMessage(), event.get("id"));
                } else {
                    jdbc.update("UPDATE payment_outbox SET status = 'PENDING', attempts = ?, last_error = ?, available_at = ? WHERE id = ?",
                            attempts, e.getMessage(), LocalDateTime.now().plusMinutes(attempts * 2L), event.get("id"));
                }
            }
        }
    }

    private void publishEvent(Map<String, Object> event) throws Exception {
        String eventId = (String) event.get("event_id");
        String eventType = (String) event.get("event_type");
        String payload = (String) event.get("payload");
        String aggregateType = (String) event.get("aggregate_type");
        String aggregateId = (String) event.get("aggregate_id");
        String correlationId = (String) event.get("correlation_id");
        String causationId = (String) event.get("causation_id");

        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        properties.setMessageId(eventId);
        properties.setType(eventType);
        properties.setHeader("aggregate_type", aggregateType);
        properties.setHeader("aggregate_id", aggregateId);
        properties.setHeader("correlation_id", correlationId);
        properties.setHeader("causation_id", causationId);
        properties.setHeader("source-service", "payment-service");
        properties.setTimestamp(java.util.Date.from(LocalDateTime.now().atZone(java.time.ZoneId.systemDefault()).toInstant()));

        Message message = new Message(payload.getBytes(), properties);
        CorrelationData correlationData = new CorrelationData(eventId);

        rabbitTemplate.convertAndSend(EXCHANGE, eventType, message, correlationData);
        CorrelationData.Confirm confirmation = correlationData.getFuture().get(5, TimeUnit.SECONDS);
        if (!confirmation.isAck() || correlationData.getReturned() != null) {
            throw new IllegalStateException("RabbitMQ did not confirm event " + eventId);
        }
    }

    public void writeToOutbox(String eventType, String aggregateType, String aggregateId, 
                              String correlationId, String causationId, Object payload) {
        try {
            JsonNode encodedPayload = objectMapper.valueToTree(payload);
            if (encodedPayload == null || !encodedPayload.isObject()) {
                throw new IllegalArgumentException("Outbox event payload must be a JSON object");
            }
            ObjectNode eventPayload = (ObjectNode) encodedPayload;
            String eventId = eventPayload.path("eventId").asText(null);
            if (eventId == null || eventId.isBlank()) {
                eventId = UUID.randomUUID().toString();
            }
            eventPayload.put("eventId", eventId);
            eventPayload.put("eventType", eventType);
            if (!eventPayload.hasNonNull("producer")) {
                eventPayload.put("producer", "payment-service");
            }
            if (!eventPayload.hasNonNull("occurredAt")) {
                eventPayload.put("occurredAt", LocalDateTime.now().toString());
            }
            String jsonPayload = objectMapper.writeValueAsString(eventPayload);

            jdbc.update("""
                INSERT INTO payment_outbox (event_id, event_type, aggregate_type, aggregate_id, aggregate_version, correlation_id, causation_id, payload, status, attempts, available_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?)
                """, eventId, eventType, aggregateType, aggregateId, 1,
                    correlationId, causationId, jsonPayload, LocalDateTime.now(), LocalDateTime.now());
            log.debug("Wrote event {} to outbox", eventId);
        } catch (Exception e) {
            log.error("Failed to write event to outbox: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to write event to outbox", e);
        }
    }
}
