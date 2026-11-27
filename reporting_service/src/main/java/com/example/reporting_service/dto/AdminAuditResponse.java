package com.example.reporting_service.dto;

import com.example.reporting_service.entity.OperationAudit;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public record AdminAuditResponse(
        Long id, OffsetDateTime createdAt, String actorType, String actorId, String actorName, String actorRole,
        String actionGroup, String actionCode, String targetType, String targetId, String targetLabel,
        String summary, String reason, String beforeJson, String afterJson, String correlationId
) {
    public static AdminAuditResponse from(OperationAudit row) {
        return new AdminAuditResponse(row.getId(), row.getCreatedAt().atOffset(ZoneOffset.UTC),
                row.getActorType(), row.getActorId(), row.getActorName(), row.getActorRole(), row.getActionGroup(),
                row.getAction(), row.getEntityType(), row.getEntityId(), row.getTargetLabel(), row.getSummary(),
                row.getReason(), row.getBeforeJson(), row.getAfterJson(), row.getCorrelationId());
    }
}
