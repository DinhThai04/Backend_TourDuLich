package com.example.invoice_service.service;

import com.example.invoice_service.dto.internal.PaymentSettlementEvent;
import com.example.invoice_service.entity.Invoice;
import com.example.invoice_service.entity.InvoiceStatus;
import com.example.invoice_service.repository.InvoiceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Idempotent consumer for payment settlement/refund events backed by invoice_inbox.
 * Never polls foreign payment_outbox tables.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentEventConsumer {

    private final JdbcTemplate jdbc;
    private final InvoiceRepository invoiceRepository;
    private final ObjectMapper objectMapper;

    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Transactional
    public void processPaymentEvent(String eventId, String eventType, String payload) {
        if (eventId != null && !eventId.isBlank()) {
            int inserted = jdbc.update("""
                    INSERT IGNORE INTO invoice_inbox (event_id, event_type, processed_at)
                    VALUES (?, ?, ?)
                    """, eventId, eventType, LocalDateTime.now(VIETNAM_ZONE));
            if (inserted == 0) {
                log.debug("Duplicate payment event {} ignored by invoice_inbox", eventId);
                return;
            }
        }

        PaymentSettlementEvent settlementEvent;
        try {
            settlementEvent = objectMapper.readValue(payload, PaymentSettlementEvent.class);
        } catch (Exception e) {
            log.error("Failed to parse payment event payload: {}", e.getMessage());
            throw new IllegalArgumentException("Invalid payment event payload", e);
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

        Optional<Invoice> existing = invoiceRepository.findByBookingId(bookingId);
        if (existing.isPresent()) {
            Invoice invoice = existing.get();
            if (InvoiceStatus.PAID.equals(invoice.getStatus())) {
                log.debug("Invoice already exists and is PAID for booking {}", bookingId);
                return;
            }
            invoice.setStatus(InvoiceStatus.PAID);
            invoice.setDayOfPay(event.getPaidAt() != null ? event.getPaidAt() : LocalDateTime.now(VIETNAM_ZONE));
            invoice.setPaymentMethodId(event.getPayMethodId() != null ? event.getPayMethodId() : 1);
            invoice.setTotalBookingTourExpense(BigDecimal.valueOf(event.getAmountVnd()));
            invoiceRepository.save(invoice);
            log.info("Updated invoice for booking {} from payment event", bookingId);
        } else {
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
