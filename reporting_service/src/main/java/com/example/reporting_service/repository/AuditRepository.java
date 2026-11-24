package com.example.reporting_service.repository;

import com.example.reporting_service.entity.OperationAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AuditRepository extends JpaRepository<OperationAudit, Long> {

    List<OperationAudit> findByActorId(String actorId);

    List<OperationAudit> findByEntityTypeAndEntityId(String entityType, String entityId);

    List<OperationAudit> findByOccurredAtBetween(LocalDateTime from, LocalDateTime through);

    List<OperationAudit> findByCorrelationId(String correlationId);
}