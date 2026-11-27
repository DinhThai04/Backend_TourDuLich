package com.example.booking_service.repository.httpclient;

import com.example.booking_service.config.NotificationFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@FeignClient(name = "notification-service-internal", url = "${app.services.notification-base-url:http://localhost:8093/notification}", configuration = NotificationFeignConfiguration.class)
public interface NotificationInternalClient {

    @PostMapping("/internal/notifications/mail")
    Map<String, Boolean> enqueueMail(@RequestBody MailEnqueueRequest request);

    @GetMapping("/internal/notifications/mail/bookings/{bookingId}")
    java.util.List<Map<String, Object>> getDeliveries(@PathVariable("bookingId") Integer bookingId);

    @PostMapping("/internal/notifications/mail/bookings/{bookingId}/{id}/retry")
    void retryMail(@PathVariable("bookingId") Integer bookingId, @PathVariable("id") Long id,
            @RequestParam("confirmUncertain") boolean confirmUncertain);

    record MailEnqueueRequest(
            String eventKey,
            String eventType,
            String sourceService,
            Integer bookingId,
            Integer accountId,
            String recipient,
            String subject,
            String html,
            String text,
            java.time.LocalDateTime scheduledAt
    ) {}
}