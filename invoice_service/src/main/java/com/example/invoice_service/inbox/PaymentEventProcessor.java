package com.example.invoice_service.inbox;

import com.example.invoice_service.dto.internal.PaymentSettlementEvent;
import com.example.invoice_service.entity.Invoice;
import com.example.invoice_service.entity.InvoiceStatus;
import com.example.invoice_service.repository.InvoiceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentEventProcessor {

    private final InvoiceRepository invoiceRepository;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Transactional
    public void process(String messageId, String eventType, String payload) {
        PaymentSettlementEvent event;
        try {
            event = objectMapper.readValue(payload, PaymentSettlementEvent.class);
        } catch (Exception e) {
            log.error("Failed to parse payment event payload: {}", e.getMessage());
            throw new RuntimeException("Failed to parse payment event payload", e);
        }

        if ("payment.settled.v1".equals(eventType)) {
            handleSettledPayment(event);
        } else if ("payment.refunded.v1".equals(eventType)) {
            handleRefundedPayment(event);
        } else {
            log.warn("Unknown event type: {}", eventType);
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