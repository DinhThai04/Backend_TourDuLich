package com.example.invoice_service.controller;

import com.example.invoice_service.dto.internal.InternalInvoiceResponse;
import com.example.invoice_service.entity.Invoice;
import com.example.invoice_service.repository.InvoiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/internal/invoice")
@RequiredArgsConstructor
public class InternalInvoiceController {

    private final InvoiceRepository invoiceRepository;

    @GetMapping("/{id}")
    public ResponseEntity<InternalInvoiceResponse> getInvoiceById(@PathVariable Integer id) {
        return invoiceRepository.findById(id)
                .map(this::toInternalResponse)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/booking/{bookingId}")
    public ResponseEntity<InternalInvoiceResponse> getInvoiceByBookingId(@PathVariable Integer bookingId) {
        return invoiceRepository.findByBookingId(bookingId)
                .map(this::toInternalResponse)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/batch")
    public ResponseEntity<List<InternalInvoiceResponse>> getInvoicesBatch(@RequestBody List<Integer> bookingIds) {
        if (bookingIds == null || bookingIds.isEmpty() || bookingIds.size() > 100 || bookingIds.stream().anyMatch(id -> id == null || id < 1)) {
            return ResponseEntity.badRequest().build();
        }
        List<Invoice> invoices = invoiceRepository.findByBookingIdIn(bookingIds.stream().distinct().toList());
        List<InternalInvoiceResponse> responses = invoices.stream()
                .map(this::toInternalResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/users/{accountId}/has-history")
    public ResponseEntity<java.util.Map<String, Object>> hasUserHistory(@PathVariable Long accountId) {
        boolean hasHistory = invoiceRepository.existsByAccountId(accountId.intValue());
        return ResponseEntity.ok(java.util.Map.of("hasHistory", hasHistory));
    }

    private InternalInvoiceResponse toInternalResponse(Invoice invoice) {
        return InternalInvoiceResponse.builder()
                .id(invoice.getId())
                .accountId(invoice.getAccountId())
                .bookingId(invoice.getBookingId())
                .dayOfPay(invoice.getDayOfPay())
                .paymentMethodId(invoice.getPaymentMethodId())
                .status(invoice.getStatus().name())
                .totalBookingTourExpense(invoice.getTotalBookingTourExpense())
                .build();
    }
}