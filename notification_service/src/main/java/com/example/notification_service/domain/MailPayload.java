package com.example.notification_service.domain;

public record MailPayload(int version, String subject, String html, String text) {
}
