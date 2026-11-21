package com.example.notification_service.domain;

public record MailOutboxItem(long id, String eventType, Integer bookingId, Integer accountId,
        String recipient, String payload, int attemptCount, String claimToken) {
}
