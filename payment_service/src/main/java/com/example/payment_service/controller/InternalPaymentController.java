package com.example.payment_service.controller;

import com.example.payment_service.dto.internal.InternalPaymentResponse;
import com.example.payment_service.dto.internal.InternalPaymentSettlementEvent;
import com.example.payment_service.service.InternalPaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/internal/payment")
@RequiredArgsConstructor
public class InternalPaymentController {
    private final InternalPaymentService payments;

    @GetMapping("/{id}")
    public ResponseEntity<InternalPaymentResponse> getPaymentById(@PathVariable Integer id) {
        return payments.getPaymentById(id);
    }

    @GetMapping("/booking/{bookingId}")
    public ResponseEntity<InternalPaymentResponse> getPaymentByBookingId(@PathVariable Integer bookingId) {
        return payments.getPaymentByBookingId(bookingId);
    }

    @PostMapping("/settlements/batch")
    public ResponseEntity<List<InternalPaymentResponse>> getPaymentsBatch(@RequestBody List<Integer> bookingIds) {
        return payments.getPaymentsBatch(bookingIds);
    }

    @GetMapping("/operations/amounts")
    public List<Map<String, Object>> reportAmounts(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate through) {
        return payments.reportAmounts(from, through);
    }

    @GetMapping("/operations/transactions/{bookingId}")
    public List<Map<String, Object>> invoiceTransactions(@PathVariable int bookingId) {
        return payments.invoiceTransactions(bookingId);
    }

    @GetMapping("/settlements/events/{eventId}")
    public ResponseEntity<InternalPaymentSettlementEvent> getSettlementEvent(@PathVariable String eventId) {
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/refund")
    public ResponseEntity<Map<String, Object>> refund(@RequestParam Integer bookingId, @RequestParam Long amountVnd, @RequestParam String reference) {
        return ResponseEntity.ok(payments.refund(bookingId, amountVnd, reference));
    }

    @GetMapping("/reconciliation/open-count/{accountId}")
    public ResponseEntity<Map<String, Object>> getOpenReconciliationCount(@PathVariable Long accountId) {
        return payments.getOpenReconciliationCount(accountId);
    }

    @GetMapping("/users/{accountId}/pending-transactions")
    public ResponseEntity<Map<String, Object>> getPendingTransactions(@PathVariable Long accountId) {
        return payments.getPendingTransactions(accountId);
    }

    @GetMapping("/users/{accountId}/has-history")
    public ResponseEntity<Map<String, Object>> hasUserHistory(@PathVariable Long accountId) {
        return payments.hasUserHistory(accountId);
    }

    @GetMapping("/bookings/{bookingId}/evidence")
    public ResponseEntity<Map<String, Object>> getBookingPaymentEvidence(@PathVariable Integer bookingId) {
        return payments.getBookingPaymentEvidence(bookingId);
    }

    @GetMapping("/bookings/{bookingId}/settled-ref")
    public ResponseEntity<Map<String, Object>> hasSettledReference(
            @PathVariable Integer bookingId,
            @RequestParam Long accountId,
            @RequestParam String transactionRef,
            @RequestParam Long amountVnd) {
        return payments.hasSettledReference(bookingId, accountId, transactionRef, amountVnd);
    }
}
