package com.example.payment_service.repository.httpclient;

import com.example.payment_service.config.NotificationFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@FeignClient(name = "notification-service-internal", url = "${app.services.notification-base-url:http://localhost:8093/notification}", configuration = NotificationFeignConfiguration.class)
public interface NotificationInternalClient {

    @PostMapping("/internal/notifications/mail")
    Map<String, Boolean> enqueueMail(@RequestBody MailEnqueueRequest request);

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