package com.example.reporting_service.service;

import com.example.reporting_service.dto.AuditRecordRequest;
import com.example.reporting_service.entity.OperationAudit;
import com.example.reporting_service.repository.AuditRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AuditRepository repository;

    @Transactional
    public Long record(AuditRecordRequest request) {
        OperationAudit audit = OperationAudit.builder()
                .actorId(request.actorId())
                .action(request.action())
                .entityType(request.entityType())
                .entityId(request.entityId())
                .detail(request.detail() == null ? null : request.detail().toString())
                .correlationId(request.correlationId())
                .causationId(request.causationId())
                .occurredAt(request.occurredAt() != null ? request.occurredAt() : LocalDateTime.now())
                .build();
        Long id = repository.save(audit).getId();
        log.debug("Audit recorded: {} {} {} {}", request.action(), request.entityType(), request.entityId(),
                request.correlationId());
        return id;
    }

    @Transactional(readOnly = true)
    public Page<OperationAudit> page(int page) {
        if (page < 0 || page > 10000) throw new IllegalArgumentException("Invalid audit page");
        return repository.findAll(PageRequest.of(page, 20, Sort.by(Sort.Direction.DESC, "id")));
    }
}
