package com.example.invoice_service.service;

import com.example.invoice_service.dto.internal.InternalInvoiceResponse;
import com.example.invoice_service.dto.internal.PaymentSettlementEvent;
import com.example.invoice_service.entity.Invoice;
import com.example.invoice_service.entity.InvoiceStatus;
import com.example.invoice_service.repository.InvoiceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentEventConsumer {

    private final JdbcTemplate jdbc;
    private final InvoiceRepository invoiceRepository;

    @Value("${app.payment-event-consumer.enabled:false}")
    private boolean enabled;

    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /**
     * Polls the payment service outbox for new settlement events.
     * In Phase 2 (shared DB), we can query the payment_outbox table directly.
     * In Phase 3+ (separate DBs), this will be replaced by RabbitMQ consumer.
     * DISABLED by default in Phase 3+ - set app.payment-event-consumer.enabled=true to enable.
     */
    @Scheduled(fixedDelay = 5000) // Poll every 5 seconds
    @Transactional
    public void consumePaymentEvents() {
        if (!enabled) {
            return;
        }
        var events = jdbc.queryForList("""
            SELECT * FROM payment_outbox 
            WHERE event_type IN ('payment.settled.v1', 'payment.refunded.v1')
            AND processed = FALSE
            ORDER BY created_at
            LIMIT 50
            """);

        for (var event : events) {
            try {
                processEvent(event);
                // Mark as processed
                jdbc.update("UPDATE payment_outbox SET processed = TRUE, processed_at = ? WHERE id = ?",
                        LocalDateTime.now(VIETNAM_ZONE), event.get("id"));
            } catch (Exception e) {
                log.error("Failed to process payment event {}: {}", event.get("id"), e.getMessage(), e);
                // Increment retry count
                jdbc.update("UPDATE payment_outbox SET retry_count = retry_count + 1 WHERE id = ?", event.get("id"));
            }
        }
    }
    // ... rest of the class remains the same

    private void processEvent(Map<String, Object> event) {
        String eventType = (String) event.get("event_type");
        String payload = (String) event.get("payload");

        // Parse JSON payload
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        PaymentSettlementEvent settlementEvent;
        try {
            settlementEvent = mapper.readValue(payload, PaymentSettlementEvent.class);
        } catch (Exception e) {
            log.error("Failed to parse payment event payload: {}", e.getMessage());
            return;
        }

        if ("payment.settled.v1".equals(eventType)) {
            handleSettledPayment(settlementEvent);
        } else if ("payment.refunded.v1".equals(eventType)) {
            handleRefundedPayment(settlementEvent);
        }
    }

    private void handleSettledPayment(PaymentSettlementEvent event) {
        Integer bookingId = event.getBookingId();
        if (bookingId == null) {
            log.warn("Payment settled event missing bookingId: {}", event);
            return;
        }

        // Check if invoice already exists
        Optional<Invoice> existing = invoiceRepository.findByBookingId(bookingId);
        if (existing.isPresent()) {
            Invoice invoice = existing.get();
            if (InvoiceStatus.PAID.equals(invoice.getStatus())) {
                log.debug("Invoice already exists and is PAID for booking {}", bookingId);
                return;
            }
            // Update existing invoice
            invoice.setStatus(InvoiceStatus.PAID);
            invoice.setDayOfPay(event.getPaidAt() != null ? event.getPaidAt() : LocalDateTime.now(VIETNAM_ZONE));
            invoice.setPaymentMethodId(event.getPayMethodId() != null ? event.getPayMethodId() : 1);
            invoice.setTotalBookingTourExpense(BigDecimal.valueOf(event.getAmountVnd()));
            invoiceRepository.save(invoice);
            log.info("Updated invoice for booking {} from payment event", bookingId);
        } else {
            // Create new invoice
            Invoice invoice = Invoice.builder()
                    .accountId(event.getAccountId())
                    .bookingId(bookingId)
                    .dayOfPay(event.getPaidAt() != null ? event.getPaidAt() : LocalDateTime.now(VIETNAM_ZONE))
                    .paymentMethodId(event.getPayMethodId() != null ? event.getPayMethodId() : 1)
                    .status(InvoiceStatus.PAID)
                    .totalBookingTourExpense(BigDecimal.valueOf(event.getAmountVnd()))
                    .build();
            invoiceRepository.save(invoice);
            log.info("Created invoice for booking {} from payment event", bookingId);
        }
    }

    private void handleRefundedPayment(PaymentSettlementEvent event) {
        Integer bookingId = event.getBookingId();
        if (bookingId == null) return;

        Optional<Invoice> existing = invoiceRepository.findByBookingId(bookingId);
        existing.ifPresent(invoice -> {
            invoice.setStatus(InvoiceStatus.REFUNDED);
            invoiceRepository.save(invoice);
            log.info("Marked invoice as REFUNDED for booking {} from payment event", bookingId);
        });
    }
}