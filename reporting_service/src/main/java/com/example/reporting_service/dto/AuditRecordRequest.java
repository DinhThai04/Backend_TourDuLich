package com.example.reporting_service.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public record AuditRecordRequest(
        @NotBlank @Size(max = 80) String actorId,
        @NotBlank @Size(max = 120) String action,
        @NotBlank @Size(max = 80) String entityType,
        @NotBlank @Size(max = 80) String entityId,
        JsonNode detail,
        @Size(max = 80) String correlationId,
        @Size(max = 80) String causationId,
        LocalDateTime occurredAt) {
}
