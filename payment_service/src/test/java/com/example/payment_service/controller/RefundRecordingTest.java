package com.example.payment_service.controller;

import com.example.payment_service.outbox.OutboxPublisher;
import com.example.payment_service.repository.PaymentRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RefundRecordingTest {
    @Test
    void recordsARefundOnceAndRejectsConflictingEvidence() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        OutboxPublisher outbox = mock(OutboxPublisher.class);
        var controller = new InternalPaymentController(new com.example.payment_service.service.InternalPaymentService(
                mock(PaymentRepository.class), jdbc, outbox));
        var paidAt = LocalDateTime.of(2026, 11, 1, 7, 0);
        when(jdbc.queryForList(contains("FROM payment WHERE booking_id="), eq(50))).thenReturn(
                List.of(Map.of("status", "PAID", "account_id", 10, "total_booking_tour_expense", 100000, "day_of_pay", paidAt, "pay_method_id", 1)),
                List.of(Map.of("status", "REFUNDED", "total_booking_tour_expense", 100000)));
        when(jdbc.queryForObject(contains("FROM payment_outbox"), eq(Integer.class), eq("50"), eq("receipt-a"))).thenReturn(1);
        assertEquals(200, controller.refund(50, 100000L, "receipt-a").getStatusCode().value());
        assertEquals(200, controller.refund(50, 100000L, "receipt-a").getStatusCode().value());
        assertThrows(ResponseStatusException.class, () -> controller.refund(50, 100000L, "receipt-b"));
        verify(outbox).writeToOutbox(eq("payment.refunded.v1"), eq("Payment"), eq("50"), eq("50"), eq("50"),
                eq(Map.of("bookingId", 50, "accountId", 10, "amountVnd", 100000L, "payMethodId", 1,
                        "paidAt", paidAt.toString(), "refundReference", "receipt-a", "status", "REFUNDED")));
    }
}
