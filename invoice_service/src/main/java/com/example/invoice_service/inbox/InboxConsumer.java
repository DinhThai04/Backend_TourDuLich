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

    private boolean isDuplicate(String messageId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + INBOX_TABLE + " WHERE message_id = ? AND processed = TRUE",
                Integer.class, messageId);
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
        String sql = "INSERT INTO " + INBOX_TABLE + " (message_id, event_type, aggregate_type, aggregate_id, correlation_id, causation_id, source_service, payload, processed, processed_at, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?, ?) " +
                "ON DUPLICATE KEY UPDATE processed = TRUE, processed_at = ?";
        jdbc.update(sql, messageId, eventType, aggregateType, aggregateId, correlationId, causationId, sourceService, payload,
                LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now());
    }

    private void handleFailure(String messageId, String eventType, String payload,
                               long deliveryTag, Channel channel, Exception e) throws Exception {
        Integer retryCount = jdbc.queryForObject(
                "SELECT retry_count FROM " + INBOX_TABLE + " WHERE message_id = ?",
                Integer.class, messageId);

        int nextRetry = (retryCount == null) ? 1 : retryCount + 1;

        if (nextRetry > MAX_RETRIES) {
            moveToDLQ(messageId, eventType, payload, e.getMessage());
            channel.basicAck(deliveryTag, false);
            log.error("Event {} moved to DLQ after {} retries", messageId, MAX_RETRIES);
        } else {
            long delay = BACKOFF_MS[Math.min(nextRetry - 1, BACKOFF_MS.length - 1)];
            String sql = "INSERT INTO " + INBOX_TABLE + " (message_id, event_type, payload, retry_count, last_error, last_attempt_at, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE retry_count = ?, last_error = ?, last_attempt_at = ?";
            jdbc.update(sql, messageId, eventType, payload, nextRetry, e.getMessage(), LocalDateTime.now(), LocalDateTime.now(),
                    nextRetry, e.getMessage(), LocalDateTime.now());

            channel.basicNack(deliveryTag, false, true);
            log.warn("Event {} scheduled for retry {} in {} ms", messageId, nextRetry, delay);
        }
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