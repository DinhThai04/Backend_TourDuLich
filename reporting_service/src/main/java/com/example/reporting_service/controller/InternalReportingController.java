package com.example.reporting_service.controller;

import com.example.reporting_service.dto.AuditRecordRequest;
import com.example.reporting_service.dto.AdminAuditResponse;
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
    private final com.example.reporting_service.consumer.ReportingEventProcessor events;

    @PostMapping("/admin-audit/events")
    public ResponseEntity<Void> ingestAdminAudit(@RequestBody com.fasterxml.jackson.databind.JsonNode event) {
        if (!event.isObject() || !"admin.audit.v1".equals(event.path("eventType").asText())) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST);
        }
        events.process(new org.springframework.amqp.core.Message(
                event.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return ResponseEntity.noContent().build();
    }

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

    @GetMapping("/admin-audit")
    public Page<AdminAuditResponse> adminAudit(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword, @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String actionGroup, @RequestParam(required = false) String targetType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        return auditService.adminPage(page, size, keyword, actorId, actionGroup, targetType, fromDate, toDate)
                .map(AdminAuditResponse::from);
    }

    @GetMapping("/admin-audit/actors")
    public java.util.List<com.example.reporting_service.repository.AuditRepository.ActorOption> administrativeActors(
            @RequestParam(defaultValue = "") String keyword) {
        return auditService.actors(keyword);
    }

    @GetMapping("/admin-audit/{id}")
    public AdminAuditResponse adminDetail(@org.springframework.web.bind.annotation.PathVariable long id) {
        return AdminAuditResponse.from(auditService.adminDetail(id));
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
