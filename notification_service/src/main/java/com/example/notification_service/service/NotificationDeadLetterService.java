package com.example.notification_service.service;

import com.example.notification_service.config.RabbitMQConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class NotificationDeadLetterService {

    private static final List<String> FORWARDED_HEADERS =
            List.of("aggregate_type", "aggregate_id", "correlation_id", "causation_id", "source-service");

    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public NotificationDeadLetterService(JdbcTemplate jdbc, RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void record(Message message, int attempts, String failureType) {
        MessageProperties properties = message.getMessageProperties();
        String messageId = properties.getMessageId();
        if (messageId == null || messageId.isBlank() || messageId.length() > 36) {
            messageId = UUID.randomUUID().toString();
        }
        String eventType = properties.getType();
        if (eventType == null || eventType.isBlank() || eventType.length() > 120) {
            eventType = "unknown";
        }
        jdbc.update("""
                INSERT INTO notification_dead_letter(message_id,event_type,payload,headers,error,attempts,created_at)
                VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE error=VALUES(error),attempts=GREATEST(attempts,VALUES(attempts))
                """, messageId, eventType, new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8),
                safeHeaders(properties), failureType, attempts);
    }

    @Transactional
    public boolean retry(String messageId) {
        Optional<Map<String, Object>> row = jdbc.query("""
                SELECT message_id,event_type,payload,headers FROM notification_dead_letter
                WHERE message_id=? FOR UPDATE
                """, rs -> rs.next()
                ? Optional.of(Map.of(
                    "message_id", rs.getString("message_id"),
                    "event_type", rs.getString("event_type"),
                    "payload", rs.getString("payload"),
                    "headers", rs.getString("headers") == null ? "{}" : rs.getString("headers")))
                : Optional.empty(), messageId);
        if (row.isEmpty()) {
            return false;
        }

        MessageProperties properties = new MessageProperties();
        properties.setMessageId((String) row.get().get("message_id"));
        properties.setType((String) row.get().get("event_type"));
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        restoreHeaders(properties, (String) row.get().get("headers"));
        properties.setHeader("x-notification-attempt", 0);
        Message message = new Message(((String) row.get().get("payload")).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                properties);

        publish(RabbitMQConfig.NOTIFICATION_MAIL_QUEUE, message);
        jdbc.update("DELETE FROM notification_dead_letter WHERE message_id=?", messageId);
        return true;
    }

    @Transactional
    public boolean discard(String messageId) {
        return jdbc.update("DELETE FROM notification_dead_letter WHERE message_id=?", messageId) == 1;
    }

    private String safeHeaders(MessageProperties properties) {
        ObjectNode node = objectMapper.createObjectNode();
        for (String key : FORWARDED_HEADERS) {
            Object value = properties.getHeaders().get(key);
            if (value != null) {
                node.put(key, String.valueOf(value));
            }
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize notification event headers", exception);
        }
    }

    private void restoreHeaders(MessageProperties properties, String json) {
        try {
            JsonNode headers = objectMapper.readTree(json);
            for (String key : FORWARDED_HEADERS) {
                JsonNode value = headers.get(key);
                if (value != null && !value.isNull()) {
                    properties.setHeader(key, value.asText());
                }
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored notification dead-letter headers are invalid", exception);
        }
    }

    private void publish(String queue, Message message) {
        CorrelationData confirmation = new CorrelationData(UUID.randomUUID().toString());
        rabbitTemplate.send("", queue, message, confirmation);
        try {
            CorrelationData.Confirm result = confirmation.getFuture().get(5, TimeUnit.SECONDS);
            if (!result.isAck() || confirmation.getReturned() != null) {
                throw new IllegalStateException("RabbitMQ did not confirm notification retry publish");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while confirming notification retry publish", exception);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException exception) {
            throw new IllegalStateException("Could not confirm notification retry publish", exception);
        }
    }
}
