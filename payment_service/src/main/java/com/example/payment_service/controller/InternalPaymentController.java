package com.example.payment_service.controller;

import com.example.payment_service.dto.internal.InternalPaymentResponse;
import com.example.payment_service.dto.internal.InternalPaymentSettlementEvent;
import com.example.payment_service.entity.Payment;
import com.example.payment_service.repository.PaymentRepository;
import com.example.payment_service.saga.PaymentInvoiceSaga;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/internal/payment")
@RequiredArgsConstructor
public class InternalPaymentController {

    private final PaymentRepository paymentRepository;
    private final JdbcTemplate jdbc;
    private final PaymentInvoiceSaga paymentInvoiceSaga;

    @GetMapping("/{id}")
    public ResponseEntity<InternalPaymentResponse> getPaymentById(@PathVariable Integer id) {
        return paymentRepository.findById(id)
                .map(this::toInternalResponse)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/booking/{bookingId}")
    public ResponseEntity<InternalPaymentResponse> getPaymentByBookingId(@PathVariable Integer bookingId) {
        return paymentRepository.findByBookingId(bookingId)
                .map(this::toInternalResponse)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/settlements/batch")
    public ResponseEntity<List<InternalPaymentResponse>> getPaymentsBatch(@RequestBody List<Integer> bookingIds) {
        if (bookingIds == null || bookingIds.isEmpty() || bookingIds.size() > 100 || bookingIds.stream().anyMatch(id -> id == null || id < 1)) {
            return ResponseEntity.badRequest().build();
        }
        List<Payment> payments = paymentRepository.findByBookingIdIn(bookingIds.stream().distinct().toList());
        List<InternalPaymentResponse> responses = payments.stream()
                .map(this::toInternalResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/settlements/events/{eventId}")
    public ResponseEntity<InternalPaymentSettlementEvent> getSettlementEvent(@PathVariable String eventId) {
        // This would typically read from an outbox table
        // For now, return not found - will be implemented with outbox in Phase 4
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/refund")
    public ResponseEntity<Map<String, Object>> refund(@RequestParam Integer bookingId, @RequestParam Long amountVnd, @RequestParam String reference) {
        try {
            // Check if payment exists and is paid
            var payment = jdbc.queryForList("SELECT * FROM payment WHERE booking_id = ? AND status = 'PAID'", bookingId);
            if (payment.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "No paid payment found for booking"));
            }

            // Update payment status to refunded
            jdbc.update("UPDATE payment SET status = 'REFUNDED' WHERE booking_id = ? AND status = 'PAID'", bookingId);
            
            // Also update invoice if exists
            jdbc.update("UPDATE invoice SET status = 'REFUNDED' WHERE booking_id = ? AND status = 'PAID'", bookingId);
            
            // Publish refund event
            paymentInvoiceSaga.handleInvoiceCreationFailed(java.util.UUID.randomUUID().toString(), "REFUND_REQUESTED: " + reference);
            
            return ResponseEntity.ok(Map.of("success", true, "bookingId", bookingId, "amountVnd", amountVnd));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    private InternalPaymentResponse toInternalResponse(Payment payment) {
        return InternalPaymentResponse.builder()
                .id(payment.getId())
                .accountId(payment.getAccountId())
                .bookingId(payment.getBookingId())
                .dayOfPay(payment.getDayOfPay())
                .payMethodId(payment.getPayMethodId())
                .status(payment.getStatus().name())
                .totalBookingTourExpense(payment.getTotalBookingTourExpense())
                .build();
    }
}