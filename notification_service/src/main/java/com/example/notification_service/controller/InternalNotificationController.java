package com.example.notification_service.controller;

import com.example.notification_service.dto.MailEnqueueRequest;
import com.example.notification_service.repository.MailOutboxRepository;
import com.example.notification_service.service.MailEnqueueService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/internal/notifications/mail")
public class InternalNotificationController {

    private final MailEnqueueService enqueueService;
    private final MailOutboxRepository repository;
    private final Clock clock;

    public InternalNotificationController(MailEnqueueService enqueueService,
            MailOutboxRepository repository, Clock clock) {
        this.enqueueService = enqueueService;
        this.repository = repository;
        this.clock = clock;
    }

    @PostMapping
    public ResponseEntity<Map<String, Boolean>> enqueue(@Valid @RequestBody MailEnqueueRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("created", enqueueService.enqueue(request)));
    }

    @GetMapping("/bookings/{bookingId}")
    public List<Map<String, Object>> deliveries(@PathVariable int bookingId) {
        return repository.byBooking(bookingId);
    }

    @PostMapping("/bookings/{bookingId}/{id}/retry")
    public ResponseEntity<Void> retry(@PathVariable int bookingId, @PathVariable long id) {
        if (!repository.retry(bookingId, id, LocalDateTime.now(clock)))
            throw new IllegalStateException("Only failed, unsent mail can be retried");
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/users/{accountId}/has-history")
    public ResponseEntity<Map<String, Object>> hasUserHistory(@PathVariable long accountId) {
        return ResponseEntity.ok(Map.of("hasHistory", repository.hasAccountHistory(accountId)));
    }
}
