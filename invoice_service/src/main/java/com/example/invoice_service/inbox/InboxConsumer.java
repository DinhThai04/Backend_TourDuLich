package com.example.invoice_service.inbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class InboxConsumer {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PaymentEventProcessor paymentEventProcessor;
    private final RabbitTemplate rabbitTemplate;

    private static final String INBOX_TABLE = "invoice_inbox";
    private static final int MAX_RETRIES = 5;
    private static final long[] BACKOFF_MS = {1000, 2000, 4000, 8000, 16000, 32000};

    @RabbitListener(queues = "invoice.payment.events", ackMode = "MANUAL")
    @Transactional
    public void consumePaymentEvents(Message message, Channel channel) throws Exception {
        String messageId = message.getMessageProperties().getMessageId();
        String eventType = message.getMessageProperties().getType();
        long deliveryTag = message.getMessageProperties().getDeliveryTag();

        try {
            if (isDuplicate(messageId)) {
                log.debug("Duplicate event detected: {}, acknowledging", messageId);
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
            log.debug("Successfully processed event: {}", messageId);

        } catch (Exception e) {
            log.error("Failed to process event {}: {}", messageId, e.getMessage(), e);
            handleFailure(messageId, eventType, new String(message.getBody()), deliveryTag, channel, e);
        }
    }

    private static final String CONSUMER_NAME = "invoice-service";

    private boolean isDuplicate(String messageId) {
        if (messageId == null || messageId.isBlank()) return false;
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + INBOX_TABLE + " WHERE consumer = ? AND event_id = ? AND processed_at IS NOT NULL",
                Integer.class, CONSUMER_NAME, messageId);
        return count != null && count > 0;
    }

    private void processEvent(String messageId, String eventType, String payload,
                              String aggregateType, String aggregateId, String correlationId,
                              String causationId, String sourceService) {
        if ("payment.settled.v1".equals(eventType) || "payment.refunded.v1".equals(eventType)) {
            paymentEventProcessor.process(messageId, eventType, payload);
        } else {
            log.warn("Unknown event type: {}", eventType);
        }
    }

    private void markProcessed(String messageId, String eventType, String payload,
                               String aggregateType, String aggregateId, String correlationId,
                               String causationId, String sourceService) {
        String eventId = (messageId != null && !messageId.isBlank()) ? messageId : UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        String sql = "INSERT INTO " + INBOX_TABLE + " (consumer, event_id, event_type, received_at, processed_at) " +
                "VALUES (?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE processed_at = VALUES(processed_at)";
        jdbc.update(sql, CONSUMER_NAME, eventId, eventType != null ? eventType : "unknown", now, now);
    }

    private void handleFailure(String messageId, String eventType, String payload,
                               long deliveryTag, Channel channel, Exception e) throws Exception {
        String eventId = (messageId != null && !messageId.isBlank()) ? messageId : UUID.randomUUID().toString();
        try {
            moveToDLQ(eventId, eventType != null ? eventType : "unknown", payload, e.getMessage());
        } catch (Exception dlqEx) {
            log.error("Failed to persist invoice DLQ for event {}: {}", eventId, dlqEx.getMessage());
        }
        channel.basicAck(deliveryTag, false);
        log.error("Event {} moved to DLQ after processing error: {}", eventId, e.getMessage());
    }

    private void moveToDLQ(String messageId, String eventType, String payload, String error) {
        jdbc.update("""
            INSERT INTO invoice_dead_letter (message_id, event_type, payload, error, created_at)
            VALUES (?, ?, ?, ?, ?)
            """, messageId, eventType, payload, error, LocalDateTime.now());
    }

    @Transactional
    public void retryDeadLetter(String messageId) {
        var dlqEntry = jdbc.queryForMap("SELECT * FROM invoice_dead_letter WHERE message_id = ?", messageId);
        if (dlqEntry == null) {
            throw new IllegalArgumentException("Dead letter entry not found: " + messageId);
        }

        String eventType = (String) dlqEntry.get("event_type");
        String payload = (String) dlqEntry.get("payload");

        processEvent(messageId, eventType, payload, null, null, null, null, null);
        markProcessed(messageId, eventType, payload, null, null, null, null, null);

        jdbc.update("DELETE FROM invoice_dead_letter WHERE message_id = ?", messageId);
        log.info("Successfully retried dead letter event: {}", messageId);
    }
}