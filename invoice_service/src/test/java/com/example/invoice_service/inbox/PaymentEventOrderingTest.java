package com.example.invoice_service.inbox;

import com.example.invoice_service.entity.Invoice;
import com.example.invoice_service.entity.InvoiceStatus;
import com.example.invoice_service.repository.InvoiceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentEventOrderingTest {
    @Test
    void aRefundBeforeSettlementDoesNotReopenTheInvoice() {
        var repo = mock(InvoiceRepository.class);
        var saved = new AtomicReference<Invoice>();
        when(repo.findByBookingId(50)).thenAnswer(inv -> Optional.ofNullable(saved.get()));
        when(repo.save(any(Invoice.class))).thenAnswer(inv -> {
            Invoice invoice = inv.getArgument(0);
            saved.set(invoice);
            return invoice;
        });
        var processor = new PaymentEventProcessor(repo, new ObjectMapper().findAndRegisterModules());
        String event = """
            {"bookingId":50,"accountId":10,"amountVnd":3350000,"payMethodId":1,"paidAt":"2026-11-01T07:30:00"}
            """;
        processor.process("refund", "payment.refunded.v1", event);
        processor.process("settled", "payment.settled.v1", event);
        assertEquals(InvoiceStatus.REFUNDED, saved.get().getStatus());
    }
}
