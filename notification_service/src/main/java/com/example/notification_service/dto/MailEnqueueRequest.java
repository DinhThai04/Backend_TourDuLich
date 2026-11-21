package com.example.notification_service.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public record MailEnqueueRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9:._-]{1,191}") String eventKey,
        @NotBlank @Size(max = 40) String eventType,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,40}") String sourceService,
        Integer bookingId,
        Integer accountId,
        @NotBlank @Email @Size(max = 254) String recipient,
        @NotBlank @Size(max = 200) String subject,
        @NotBlank String html,
        String text,
        @NotNull LocalDateTime scheduledAt) {
}
