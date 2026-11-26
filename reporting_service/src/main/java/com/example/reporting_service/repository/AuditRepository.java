package com.example.reporting_service.repository;

import com.example.reporting_service.entity.OperationAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AuditRepository extends JpaRepository<OperationAudit, Long>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<OperationAudit> {

    interface ActorOption {
        String getActorId();
        String getActorName();
        String getActorRole();
    }

    @org.springframework.data.jpa.repository.Query("""
        select a.actorId as actorId, a.actorName as actorName, a.actorRole as actorRole
        from OperationAudit a where a.id in (
            select max(e.id) from OperationAudit e
            where e.actorType in ('ADMIN','STAFF','TOUR_GUIDE') and e.actorName is not null
            group by e.actorId
        ) and (:term = '' or lower(a.actorName) like :term escape '!' or a.actorId = :actorId)
        order by a.actorName, a.actorId
        """)
    List<ActorOption> findAdministrativeActors(String term, String actorId, org.springframework.data.domain.Pageable page);

    List<OperationAudit> findByActorId(String actorId);

    List<OperationAudit> findByEntityTypeAndEntityId(String entityType, String entityId);

    List<OperationAudit> findByOccurredAtBetween(LocalDateTime from, LocalDateTime through);

    List<OperationAudit> findByCorrelationId(String correlationId);
}