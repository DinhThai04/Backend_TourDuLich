package com.example.reporting_service.consumer;

import com.example.reporting_service.entity.OperationAudit;
import com.example.reporting_service.repository.AuditRepository;
import com.example.reporting_service.repository.OperationsReadRepository;
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
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReportingEventConsumer {

    private final JdbcTemplate jdbc;
    private final AuditRepository auditRepository;
    private final OperationsReadRepository operationsReadRepository;

    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @RabbitListener(queues = "reporting.events", ackMode = "MANUAL")
    @Transactional
    public void consumeReportingEvents(Message message, Channel channel) throws Exception {
        String messageId = message.getMessageProperties().getMessageId();
        String eventType = message.getMessageProperties().getType();
        long deliveryTag = message.getMessageProperties().getDeliveryTag();

        try {
            if (isDuplicate(messageId)) {
                log.debug("Duplicate reporting event detected: {}, acknowledging", messageId);
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
            log.debug("Successfully processed reporting event: {}", messageId);

        } catch (Exception e) {
            log.error("Failed to process reporting event {}: {}", messageId, e.getMessage(), e);
            handleFailure(messageId, eventType, new String(message.getBody()), deliveryTag, channel, e);
        }
    }

    private boolean isDuplicate(String messageId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reporting_inbox WHERE message_id = ? AND processed = TRUE",
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

        switch (eventType) {
            case "booking.created.v1" -> handleBookingCreated(eventData, payload, correlationId);
            case "booking.confirmed.v1" -> handleBookingConfirmed(eventData, payload, correlationId);
            case "booking.cancelled.v1" -> handleBookingCancelled(eventData, payload, correlationId);
            case "booking.expired.v1" -> handleBookingExpired(eventData, payload, correlationId);
            case "payment.settled.v1" -> handlePaymentSettled(eventData, payload, correlationId);
            case "payment.refunded.v1" -> handlePaymentRefunded(eventData, payload, correlationId);
            case "tour.departure.created.v1" -> handleTourDepartureCreated(eventData);
            case "tour.departure.updated.v1" -> handleTourDepartureUpdated(eventData);
            default -> log.debug("Unhandled event type for reporting: {}", eventType);
        }
    }

    private void handleBookingCreated(Map<String, Object> event, String rawPayload, String correlationId) {
        Integer bookingId = (Integer) event.get("bookingId");
        Integer accountId = (Integer) event.get("accountId");
        Integer departureId = (Integer) event.get("departureId");
        Integer seats = (Integer) event.get("seats");
        String createdAt = (String) event.get("createdAt");
        
        // Update operations projections
        jdbc.update("""
            INSERT INTO ops_booking_summary (booking_id, account_id, departure_id, seats, status, created_at, updated_at)
            VALUES (?, ?, ?, ?, 'PENDING_PAYMENT', ?, ?)
            ON DUPLICATE KEY UPDATE seats = VALUES(seats), updated_at = VALUES(updated_at)
            """, bookingId, accountId, departureId, seats, createdAt, LocalDateTime.now(VIETNAM_ZONE));
        
        // Record audit
        recordAudit("booking", bookingId.toString(), "CREATED", "system", rawPayload, correlationId);
    }

    private void handleBookingConfirmed(Map<String, Object> event, String rawPayload, String correlationId) {
        Integer bookingId = (Integer) event.get("bookingId");
        Integer accountId = (Integer) event.get("accountId");
        Integer departureId = (Integer) event.get("departureId");
        Long amountVnd = ((Number) event.get("amountVnd")).longValue();
        
        jdbc.update("""
            UPDATE ops_booking_summary SET status = 'CONFIRMED', amount_vnd = ?, confirmed_at = ?, updated_at = ?
            WHERE booking_id = ?
            """, amountVnd, LocalDateTime.now(VIETNAM_ZONE), LocalDateTime.now(VIETNAM_ZONE), bookingId);
        
        recordAudit("booking", bookingId.toString(), "CONFIRMED", "system", rawPayload, correlationId);
    }

    private void handleBookingCancelled(Map<String, Object> event, String rawPayload, String correlationId) {
        Integer bookingId = (Integer) event.get("bookingId");
        String reason = (String) event.get("reason");
        
        jdbc.update("""
            UPDATE ops_booking_summary SET status = 'CANCELLED', cancel_reason = ?, cancelled_at = ?, updated_at = ?
            WHERE booking_id = ?
            """, reason, LocalDateTime.now(VIETNAM_ZONE), LocalDateTime.now(VIETNAM_ZONE), bookingId);
        
        recordAudit("booking", bookingId.toString(), "CANCELLED", "system", rawPayload, correlationId);
    }

    private void handleBookingExpired(Map<String, Object> event, String rawPayload, String correlationId) {
        Integer bookingId = (Integer) event.get("bookingId");
        
        jdbc.update("""
            UPDATE ops_booking_summary SET status = 'EXPIRED', expired_at = ?, updated_at = ?
            WHERE booking_id = ?
            """, LocalDateTime.now(VIETNAM_ZONE), LocalDateTime.now(VIETNAM_ZONE), bookingId);
        
        recordAudit("booking", bookingId.toString(), "EXPIRED", "system", rawPayload, correlationId);
    }

    private void handlePaymentSettled(Map<String, Object> event, String rawPayload, String correlationId) {
        Integer bookingId = (Integer) event.get("bookingId");
        Long amountVnd = ((Number) event.get("amountVnd")).longValue();
        Integer payMethodId = (Integer) event.get("payMethodId");
        String providerRef = (String) event.get("providerRef");
        
        jdbc.update("""
            UPDATE ops_booking_summary SET payment_status = 'PAID', amount_vnd = ?, pay_method_id = ?, provider_ref = ?, paid_at = ?, updated_at = ?
            WHERE booking_id = ?
            """, amountVnd, payMethodId, providerRef, LocalDateTime.now(VIETNAM_ZONE), LocalDateTime.now(VIETNAM_ZONE), bookingId);
        
        recordAudit("payment", bookingId.toString(), "SETTLED", "system", rawPayload, correlationId);
    }

    private void handlePaymentRefunded(Map<String, Object> event, String rawPayload, String correlationId) {
        Integer bookingId = (Integer) event.get("bookingId");
        String reason = (String) event.get("reason");
        
        jdbc.update("""
            UPDATE ops_booking_summary SET payment_status = 'REFUNDED', refund_reason = ?, refunded_at = ?, updated_at = ?
            WHERE booking_id = ?
            """, reason, LocalDateTime.now(VIETNAM_ZONE), LocalDateTime.now(VIETNAM_ZONE), bookingId);
        
        recordAudit("payment", bookingId.toString(), "REFUNDED", "system", rawPayload, correlationId);
    }

    private void handleTourDepartureCreated(Map<String, Object> event) {
        Integer departureId = (Integer) event.get("departureId");
        Integer tourId = (Integer) event.get("tourId");
        Integer capacity = (Integer) event.get("capacity");
        String departureDate = (String) event.get("departureDate");
        
        jdbc.update("""
            INSERT INTO ops_departure_summary (departure_id, tour_id, capacity, available_seats, departure_date, status, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, 'OPEN', ?, ?)
            ON DUPLICATE KEY UPDATE capacity = VALUES(capacity), available_seats = VALUES(available_seats), updated_at = VALUES(updated_at)
            """, departureId, tourId, capacity, capacity, departureDate, LocalDateTime.now(VIETNAM_ZONE), LocalDateTime.now(VIETNAM_ZONE));
    }

    private void handleTourDepartureUpdated(Map<String, Object> event) {
        Integer departureId = (Integer) event.get("departureId");
        Integer availableSeats = (Integer) event.get("availableSeats");
        String operationalStatus = (String) event.get("operationalStatus");
        
        jdbc.update("""
            UPDATE ops_departure_summary SET available_seats = ?, status = ?, updated_at = ?
            WHERE departure_id = ?
            """, availableSeats, operationalStatus, LocalDateTime.now(VIETNAM_ZONE), departureId);
    }

    private void recordAudit(String entityType, String entityId, String action, String actorId, Object payload, String correlationId) {
        try {
            String payloadJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
            OperationAudit audit = OperationAudit.builder()
                    .entityType(entityType)
                    .entityId(entityId)
                    .action(action)
                    .actorId(actorId)
                    .detail(payloadJson)
                    .correlationId(correlationId)
                    .occurredAt(LocalDateTime.now(VIETNAM_ZONE))
                    .build();
            auditRepository.save(audit);
        } catch (Exception e) {
            log.warn("Failed to record audit: {}", e.getMessage());
        }
    }

    private void markProcessed(String messageId, String eventType, String payload,
                               String aggregateType, String aggregateId, String correlationId,
                               String causationId, String sourceService) {
        jdbc.update("""
            INSERT INTO reporting_inbox (message_id, event_type, aggregate_type, aggregate_id, correlation_id, causation_id, source_service, payload, processed, processed_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?, ?)
            ON DUPLICATE KEY UPDATE processed = TRUE, processed_at = ?
            """, messageId, eventType, aggregateType, aggregateId, correlationId, causationId, sourceService, payload,
                LocalDateTime.now(VIETNAM_ZONE), LocalDateTime.now(VIETNAM_ZONE), LocalDateTime.now(VIETNAM_ZONE));
    }

    private void handleFailure(String messageId, String eventType, String payload,
                               long deliveryTag, Channel channel, Exception e) throws Exception {
        Integer retryCount = jdbc.queryForObject(
                "SELECT retry_count FROM reporting_inbox WHERE message_id = ?",
                Integer.class, messageId);

        int nextRetry = (retryCount == null) ? 1 : retryCount + 1;
        long[] BACKOFF_MS = {1000, 2000, 4000, 8000, 16000, 32000};

        if (nextRetry > 5) {
            moveToDLQ(messageId, eventType, payload, e.getMessage());
            channel.basicAck(deliveryTag, false);
            log.error("Reporting event {} moved to DLQ after 5 retries", messageId);
        } else {
            long delay = BACKOFF_MS[Math.min(nextRetry - 1, BACKOFF_MS.length - 1)];
            jdbc.update("""
                INSERT INTO reporting_inbox (message_id, event_type, payload, retry_count, last_error, last_attempt_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE retry_count = ?, last_error = ?, last_attempt_at = ?
                """, messageId, eventType, payload, nextRetry, e.getMessage(), LocalDateTime.now(VIETNAM_ZONE), LocalDateTime.now(VIETNAM_ZONE),
                    nextRetry, e.getMessage(), LocalDateTime.now(VIETNAM_ZONE));

            channel.basicNack(deliveryTag, false, true);
            log.warn("Reporting event {} scheduled for retry {} in {} ms", messageId, nextRetry, delay);
        }
    }

    private void moveToDLQ(String messageId, String eventType, String payload, String error) {
        jdbc.update("""
            INSERT INTO reporting_dead_letter (message_id, event_type, payload, error, created_at)
            VALUES (?, ?, ?, ?, ?)
            """, messageId, eventType, payload, error, LocalDateTime.now(VIETNAM_ZONE));
    }
}