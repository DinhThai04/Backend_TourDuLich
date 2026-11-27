package com.example.reporting_service.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "operation_audit")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OperationAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_id", nullable = false, length = 80)
    private String actorId;

    @Column(name = "action", nullable = false, length = 120)
    private String action;

    @Column(name = "entity_type", nullable = false, length = 80)
    private String entityType;

    @Column(name = "entity_id", nullable = false, length = 80)
    private String entityId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail", columnDefinition = "JSON")
    private String detail;

    @Column(name = "correlation_id", length = 80)
    private String correlationId;

    @Column(name = "causation_id", length = 80)
    private String causationId;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(length = 16)
    private String actorType;
    @Column(length = 160)
    private String actorName;
    @Column(length = 100)
    private String actorRole;
    @Column(length = 24)
    private String actionGroup;
    @Column(length = 160)
    private String targetLabel;
    @Column(length = 240)
    private String summary;
    @Column(length = 1000)
    private String reason;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSON")
    private String beforeJson;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSON")
    private String afterJson;
    private LocalDateTime createdAt;
}