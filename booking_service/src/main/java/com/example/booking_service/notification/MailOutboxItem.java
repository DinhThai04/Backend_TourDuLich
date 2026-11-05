package com.example.booking_service.notification;

public record MailOutboxItem(long id, MailEventType eventType, Integer bookingId,
        Integer accountId, String recipient, String payload, int attemptCount,
        String claimToken) {}
