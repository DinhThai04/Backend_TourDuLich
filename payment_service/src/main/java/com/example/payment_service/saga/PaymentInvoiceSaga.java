package com.example.payment_service.saga;

import com.example.payment_service.outbox.OutboxPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentInvoiceSaga {

    private final JdbcTemplate jdbc;
    private final OutboxPublisher outboxPublisher;

    public enum SagaState {
        PAYMENT_PROCESSING,
        PAYMENT_SETTLED,
        INVOICE_CREATING,
        INVOICE_CREATED,
        COMPENSATING_REFUND,
        COMPENSATED,
        FAILED
    }

    @Transactional
    public String startPaymentSaga(int bookingId, int accountId, long amountVnd, int payMethodId, String providerRef, LocalDateTime paidAt, String provider) {
        String sagaId = java.util.UUID.randomUUID().toString();
        
        Map<String, Object> payload = Map.of(
            "bookingId", bookingId,
            "accountId", accountId,
            "amountVnd", amountVnd,
            "payMethodId", payMethodId,
            "providerRef", providerRef,
            "paidAt", paidAt.toString(),
            "provider", provider
        );
        
        String jsonPayload = toJson(payload);
        
        jdbc.update("""
            INSERT INTO payment_saga (id, saga_type, aggregate_id, status, current_step, payload, attempts, created_at, updated_at)
            VALUES (?, 'SETTLEMENT', ?, 'PAYMENT_PROCESSING', 'PAYMENT_PROCESSING', ?, 0, ?, ?)
            """, sagaId, String.valueOf(bookingId), jsonPayload, LocalDateTime.now(), LocalDateTime.now());
        
        log.info("Started payment settlement saga {} for booking {}", sagaId, bookingId);
        return sagaId;
    }

    @Transactional
    public void handlePaymentSettled(String sagaId, String providerTxnNo) {
        var saga = jdbc.queryForMap("SELECT * FROM payment_saga WHERE id = ?", sagaId);
        if (saga == null) {
            log.warn("Saga not found: {}", sagaId);
            return;
        }
        
        Map<String, Object> payload = parseJson((String) saga.get("payload"));
        int bookingId = ((Number) payload.get("bookingId")).intValue();
        int accountId = ((Number) payload.get("accountId")).intValue();
        long amountVnd = ((Number) payload.get("amountVnd")).longValue();
        int payMethodId = ((Number) payload.get("payMethodId")).intValue();
        String providerRef = (String) payload.get("providerRef");
        LocalDateTime paidAt = LocalDateTime.parse((String) payload.get("paidAt"));
        String provider = (String) payload.get("provider");
        
        // Update saga state
        jdbc.update("UPDATE payment_saga SET status = 'PAYMENT_SETTLED', current_step = 'PAYMENT_SETTLED', updated_at = ? WHERE id = ?", LocalDateTime.now(), sagaId);
        
        // Publish payment.settled event to outbox (for invoice service)
        outboxPublisher.writeToOutbox(
            "payment.settled.v1",
            "Payment",
            String.valueOf(bookingId),
            sagaId,
            sagaId,
            new java.util.LinkedHashMap<>(Map.ofEntries(
                Map.entry("eventId", java.util.UUID.randomUUID().toString()),
                Map.entry("eventType", "payment.settled.v1"),
                Map.entry("occurredAt", LocalDateTime.now().toString()),
                Map.entry("bookingId", bookingId),
                Map.entry("accountId", accountId),
                Map.entry("amountVnd", amountVnd),
                Map.entry("payMethodId", payMethodId),
                Map.entry("providerRef", providerRef),
                Map.entry("providerTxnNo", providerTxnNo),
                Map.entry("paidAt", paidAt.toString()),
                Map.entry("status", "PAID")
            ))
        );
        
        // Move to invoice creation step
        jdbc.update("UPDATE payment_saga SET status = 'INVOICE_CREATING', current_step = 'INVOICE_CREATING', updated_at = ? WHERE id = ?", LocalDateTime.now(), sagaId);
        
        log.info("Payment settled for booking {}, saga {} - published event for invoice creation", bookingId, sagaId);
    }

    @Transactional
    public void handleInvoiceCreated(String correlationId) {
        // Find saga by correlation_id (which is the sagaId)
        var saga = jdbc.queryForList("SELECT * FROM payment_saga WHERE correlation_id = ? AND saga_type = 'SETTLEMENT'", correlationId);
        if (saga.isEmpty()) {
            log.warn("No saga found for correlation_id: {}", correlationId);
            return;
        }
        
        String sagaId = (String) saga.getFirst().get("id");
        String aggregateId = (String) saga.getFirst().get("aggregate_id");
        int bookingId = Integer.parseInt(aggregateId);
        
        jdbc.update("UPDATE payment_saga SET status = 'INVOICE_CREATED', current_step = 'INVOICE_CREATED', updated_at = ? WHERE id = ?", LocalDateTime.now(), sagaId);
        
        log.info("Invoice created for booking {}, saga {} completed", bookingId, sagaId);
    }

    @Transactional
    public void handleInvoiceCreationFailed(String correlationId, String error) {
        var saga = jdbc.queryForList("SELECT * FROM payment_saga WHERE correlation_id = ? AND saga_type = 'SETTLEMENT'", correlationId);
        if (saga.isEmpty()) {
            log.warn("No saga found for correlation_id: {}", correlationId);
            return;
        }
        
        String sagaId = (String) saga.getFirst().get("id");
        String aggregateId = (String) saga.getFirst().get("aggregate_id");
        int bookingId = Integer.parseInt(aggregateId);
        
        // Start compensation - refund the payment
        jdbc.update("UPDATE payment_saga SET status = 'COMPENSATING_REFUND', current_step = 'COMPENSATING_REFUND', last_error = ?, updated_at = ? WHERE id = ?", error, LocalDateTime.now(), sagaId);
        
        // Publish payment.refunded event
        outboxPublisher.writeToOutbox(
            "payment.refunded.v1",
            "Payment",
            String.valueOf(bookingId),
            sagaId,
            sagaId,
            Map.of(
                "eventId", java.util.UUID.randomUUID().toString(),
                "eventType", "payment.refunded.v1",
                "occurredAt", LocalDateTime.now().toString(),
                "bookingId", bookingId,
                "reason", "INVOICE_CREATION_FAILED: " + error,
                "status", "REFUNDED"
            )
        );
        
        // Update payment records to refunded (invoice_service reacts via payment.refunded.v1 event)
        jdbc.update("UPDATE payment SET status = 'REFUNDED' WHERE booking_id = ? AND status = 'PAID'", bookingId);
        
        jdbc.update("UPDATE payment_saga SET status = 'COMPENSATED', current_step = 'COMPENSATED', updated_at = ? WHERE id = ?", LocalDateTime.now(), sagaId);
        
        log.info("Compensation completed for booking {}, saga {}", bookingId, sagaId);
    }

    @Scheduled(fixedDelay = 300000) // Check every 5 minutes
    @Transactional
    public void checkStuckSagas() {
        LocalDateTime timeout = LocalDateTime.now().minusMinutes(10);
        
        var stuckSagas = jdbc.queryForList("""
            SELECT * FROM payment_saga 
            WHERE saga_type = 'SETTLEMENT' 
            AND status IN ('PAYMENT_SETTLED', 'INVOICE_CREATING')
            AND updated_at < ?
            """, timeout);
        
        for (var saga : stuckSagas) {
            String sagaId = (String) saga.get("id");
            String status = (String) saga.get("status");
            
            if ("PAYMENT_SETTLED".equals(status) || "INVOICE_CREATING".equals(status)) {
                // Trigger compensation on timeout (invoice_service owns invoice table)
                handleInvoiceCreationFailed((String) saga.get("correlation_id"), "TIMEOUT: Invoice not created within 10 minutes");
            }
        }
    }

    private String toJson(Map<String, Object> map) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(map);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize payload", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse payload", e);
        }
    }
}