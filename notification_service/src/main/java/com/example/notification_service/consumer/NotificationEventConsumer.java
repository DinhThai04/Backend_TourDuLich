package com.example.notification_service.consumer;

import com.example.notification_service.domain.MailPayload;
import com.example.notification_service.repository.MailOutboxRepository;
import com.example.notification_service.service.MailTemplateService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationEventConsumer {

    private final JdbcTemplate jdbc;
    private final MailOutboxRepository mailOutboxRepository;
    private final MailTemplateService mailTemplateService;

    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @RabbitListener(queues = "notification.mail.events", ackMode = "MANUAL")
    @Transactional
    public void consumeMailEvents(Message message, Channel channel) throws Exception {
        String messageId = message.getMessageProperties().getMessageId();
        String eventType = message.getMessageProperties().getType();
        long deliveryTag = message.getMessageProperties().getDeliveryTag();

        try {
            if (isDuplicate(messageId)) {
                log.debug("Duplicate notification event detected: {}, acknowledging", messageId);
                channel.basicAck(deliveryTag, false);
                return;
            }

            String payload = new String(message.getBody());
            Map<String, Object> headers = message.getMessageProperties().getHeaders();
            
            String aggregateType = (String) headers.get("aggregate_type");
            String aggregateId = (String) headers.get("aggregate_id");
            String correlationId = (String) headers.get("correlation_id");
            String causationId = (String) headers.get("causation_id");
            String sourceService = (String) headers.get("source-service");

            processEvent(messageId, eventType, payload, aggregateType, aggregateId, correlationId, causationId, sourceService);
            
            markProcessed(messageId, eventType, payload, aggregateType, aggregateId, correlationId, causationId, sourceService);
            channel.basicAck(deliveryTag, false);
            log.debug("Successfully processed notification event: {}", messageId);

        } catch (Exception e) {
            log.error("Failed to process notification event {}: {}", messageId, e.getMessage(), e);
            handleFailure(messageId, eventType, new String(message.getBody()), deliveryTag, channel, e);
        }
    }

    private boolean isDuplicate(String messageId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_inbox WHERE message_id = ? AND processed = TRUE",
                Integer.class, messageId);
        return count != null && count > 0;
    }

    private void processEvent(String messageId, String eventType, String payload,
                              String aggregateType, String aggregateId, String correlationId,
                              String causationId, String sourceService) {
        
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Map<String, Object> eventData;
        try {
            eventData = mapper.readValue(payload, Map.class);
        } catch (Exception e) {
            log.error("Failed to parse event payload: {}", e.getMessage());
            throw new RuntimeException("Failed to parse event payload", e);
        }

        // Determine template code based on event type
        String templateCode = resolveTemplateCode(eventType);
        if (templateCode == null) {
            log.debug("No template for event type: {}", eventType);
            return;
        }

        // Extract recipient and variables from event data
        String recipient = (String) eventData.get("recipient");
        if (recipient == null) {
            recipient = (String) eventData.get("email");
        }
        
        if (recipient == null) {
            log.warn("No recipient found in event data for type: {}", eventType);
            return;
        }

        // Build variables map
        Map<String, Object> variables = new HashMap<>(eventData);
        variables.put("eventId", messageId);
        variables.put("eventType", eventType);
        variables.put("timestamp", LocalDateTime.now(VIETNAM_ZONE).toString());

        // Render template
        MailPayload mailPayload;
        try {
            mailPayload = mailTemplateService.renderTemplate(templateCode, variables);
        } catch (Exception e) {
            log.error("Failed to render template {}: {}", templateCode, e.getMessage());
            // Fallback to generic template
            mailPayload = createFallbackPayload(eventType, eventData);
        }

        // Enqueue email
        String eventKey = messageId + "-" + eventType;
        Integer bookingId = extractBookingId(eventData);
        Integer accountId = extractAccountId(eventData);
        
        mailOutboxRepository.insert(
            eventKey,
            eventType,
            sourceService != null ? sourceService : "system",
            bookingId,
            accountId,
            recipient,
            toJson(mailPayload),
            LocalDateTime.now(VIETNAM_ZONE),
            LocalDateTime.now(VIETNAM_ZONE)
        );
        
        log.info("Enqueued email for event {} to recipient {}", eventType, recipient);
    }

    private String resolveTemplateCode(String eventType) {
        return switch (eventType) {
            case "booking.created.v1" -> "BOOKING_CREATED";
            case "booking.confirmed.v1" -> "BOOKING_CONFIRMED";
            case "booking.cancelled.v1" -> "BOOKING_CANCELLED";
            case "booking.expired.v1" -> "BOOKING_EXPIRED";
            case "booking.rescheduled.v1" -> "BOOKING_RESCHEDULED";
            case "payment.settled.v1" -> "PAYMENT_CONFIRMED";
            case "payment.refunded.v1" -> "PAYMENT_REFUNDED";
            case "tour.departure.created.v1" -> "TOUR_DEPARTURE_CREATED";
            case "tour.departure.updated.v1" -> "TOUR_DEPARTURE_UPDATED";
            case "notification.mail.v1" -> "CUSTOM_NOTIFICATION";
            default -> null;
        };
    }

    private MailPayload createFallbackPayload(String eventType, Map<String, Object> eventData) {
        String subject = "SGU Tour - " + eventType;
        String html = "<h1>" + eventType + "</h1><pre>" + toJson(eventData) + "</pre>";
        return new MailPayload(1, subject, html, null);
    }

    private Integer extractBookingId(Map<String, Object> eventData) {
        Object bookingId = eventData.get("bookingId");
        if (bookingId instanceof Integer) return (Integer) bookingId;
        if (bookingId instanceof Number) return ((Number) bookingId).intValue();
        return null;
    }

    private Integer extractAccountId(Map<String, Object> eventData) {
        Object accountId = eventData.get("accountId");
        if (accountId instanceof Integer) return (Integer) accountId;
        if (accountId instanceof Number) return ((Number) accountId).intValue();
        return null;
    }

    private void markProcessed(String messageId, String eventType, String payload,
                               String aggregateType, String aggregateId, String correlationId,
                               String causationId, String sourceService) {
        jdbc.update("""
            INSERT INTO notification_inbox (message_id, event_type, aggregate_type, aggregate_id, correlation_id, causation_id, source_service, payload, processed, processed_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?, ?)
            ON DUPLICATE KEY UPDATE processed = TRUE, processed_at = ?
            """, messageId, eventType, aggregateType, aggregateId, correlationId, causationId, sourceService, payload,
                LocalDateTime.now(VIETNAM_ZONE), LocalDateTime.now(VIETNAM_ZONE), LocalDateTime.now(VIETNAM_ZONE));
    }

    private void handleFailure(String messageId, String eventType, String payload,
                               long deliveryTag, Channel channel, Exception e) throws Exception {
        Integer retryCount = jdbc.queryForObject(
                "SELECT retry_count FROM notification_inbox WHERE message_id = ?",
                Integer.class, messageId);

        int nextRetry = (retryCount == null) ? 1 : retryCount + 1;
        long[] BACKOFF_MS = {1000, 2000, 4000, 8000, 16000, 32000};

        if (nextRetry > 5) {
            moveToDLQ(messageId, eventType, payload, e.getMessage());
            channel.basicAck(deliveryTag, false);
            log.error("Notification event {} moved to DLQ after 5 retries", messageId);
        } else {
            long delay = BACKOFF_MS[Math.min(nextRetry - 1, BACKOFF_MS.length - 1)];
            jdbc.update("""
                INSERT INTO notification_inbox (message_id, event_type, payload, retry_count, last_error, last_attempt_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE retry_count = ?, last_error = ?, last_attempt_at = ?
                """, messageId, eventType, payload, nextRetry, e.getMessage(), LocalDateTime.now(VIETNAM_ZONE), LocalDateTime.now(VIETNAM_ZONE),
                    nextRetry, e.getMessage(), LocalDateTime.now(VIETNAM_ZONE));

            channel.basicNack(deliveryTag, false, true);
            log.warn("Notification event {} scheduled for retry {} in {} ms", messageId, nextRetry, delay);
        }
    }

    private void moveToDLQ(String messageId, String eventType, String payload, String error) {
        jdbc.update("""
            INSERT INTO notification_dead_letter (message_id, event_type, payload, error, created_at)
            VALUES (?, ?, ?, ?, ?)
            """, messageId, eventType, payload, error, LocalDateTime.now(VIETNAM_ZONE));
    }

    private String toJson(Object obj) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize to JSON", e);
        }
    }
}