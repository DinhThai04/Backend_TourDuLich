package com.example.reporting_service.controller;

import com.example.reporting_service.dto.AuditRecordRequest;
import com.example.reporting_service.dto.OperationsProjectionRequests;
import com.example.reporting_service.entity.OperationAudit;
import com.example.reporting_service.repository.OperationsReadRepository.OperationsReport;
import com.example.reporting_service.service.AuditService;
import com.example.reporting_service.service.OperationsReportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/internal/reports")
@RequiredArgsConstructor
public class InternalReportingController {

    private final AuditService auditService;
    private final OperationsReportService operationsService;

    @PostMapping("/audit")
    public ResponseEntity<Map<String, Long>> recordAudit(@Valid @RequestBody AuditRecordRequest request) {
        return ResponseEntity.created(URI.create("/internal/reports/audit"))
                .body(Map.of("id", auditService.record(request)));
    }

    @GetMapping("/audit")
    public Page<OperationAudit> audit(@RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        return auditService.page(page, size);
    }

    @GetMapping("/operations")
    public OperationsReport operations(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate through) {
        return operationsService.report(from, through);
    }

    @PostMapping("/projections/bookings")
    public ResponseEntity<Void> projectBooking(@Valid @RequestBody OperationsProjectionRequests.Booking request) {
        operationsService.project(request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/projections/payments")
    public ResponseEntity<Void> projectPayment(@Valid @RequestBody OperationsProjectionRequests.Payment request) {
        operationsService.project(request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/projections/invoices")
    public ResponseEntity<Void> projectInvoice(@Valid @RequestBody OperationsProjectionRequests.Invoice request) {
        operationsService.project(request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/projections/tours")
    public ResponseEntity<Void> projectTour(@Valid @RequestBody OperationsProjectionRequests.Tour request) {
        operationsService.project(request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/projections/users")
    public ResponseEntity<Void> projectUser(@Valid @RequestBody OperationsProjectionRequests.User request) {
        operationsService.project(request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/projections/refunds")
    public ResponseEntity<Void> projectRefund(@Valid @RequestBody OperationsProjectionRequests.Refund request) {
        operationsService.project(request);
        return ResponseEntity.noContent().build();
    }
}
