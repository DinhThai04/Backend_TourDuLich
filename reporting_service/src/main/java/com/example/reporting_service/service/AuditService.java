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
                .actorType("SYSTEM")
                .actionGroup(groupOf(request.entityType()))
                .createdAt(LocalDateTime.now(java.time.ZoneOffset.UTC))
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
    public Page<OperationAudit> page(int page, int size) {
        if (page < 0 || page > 10000 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid audit page");
        return repository.findAll(PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));
    }

    @Transactional(readOnly = true)
    public Page<OperationAudit> adminPage(int page, int size, String keyword, String actorId,
            String actionGroup, String targetType, java.time.LocalDate from, java.time.LocalDate to) {
        if (page < 0 || page > 10000 || size < 1 || size > 100 || keyword != null && keyword.length() > 120
                || from != null && to != null && from.isAfter(to))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Bộ lọc nhật ký không hợp lệ");
        return repository.findAll((root, query, cb) -> {
            var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            var type = root.<String>get("actorType");
            predicates.add(type.in("ADMIN", "STAFF", "TOUR_GUIDE"));
            if (actorId != null && !actorId.isBlank()) predicates.add(cb.equal(root.get("actorId"), actorId));
            if (actionGroup != null && !actionGroup.isBlank()) predicates.add(cb.equal(root.get("actionGroup"), actionGroup));
            if (targetType != null && !targetType.isBlank()) predicates.add(cb.equal(cb.upper(root.get("entityType")), targetType));
            if (keyword != null && !keyword.isBlank()) {
                String term = "%" + keyword.strip().toLowerCase(java.util.Locale.ROOT)
                        .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
                predicates.add(cb.or(cb.like(cb.lower(root.get("entityId")), term, '!'),
                        cb.like(cb.lower(root.get("targetLabel")), term, '!'),
                        cb.like(cb.lower(root.get("summary")), term, '!')));
            }
            var timestamp = root.<LocalDateTime>get("createdAt");
            var zone = java.time.ZoneId.of("Asia/Ho_Chi_Minh");
            if (from != null) predicates.add(cb.greaterThanOrEqualTo(timestamp,
                    from.atStartOfDay(zone).withZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime()));
            if (to != null) predicates.add(cb.lessThan(timestamp,
                    to.plusDays(1).atStartOfDay(zone).withZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime()));
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        }, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
    }

    @Transactional(readOnly = true)
    public java.util.List<AuditRepository.ActorOption> actors(String keyword) {
        String value = keyword.strip();
        String term = value.isEmpty() ? "" : "%" + value.toLowerCase(java.util.Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return repository.findAdministrativeActors(term, value, PageRequest.of(0, 50));
    }

    @Transactional(readOnly = true)
    public OperationAudit adminDetail(long id) {
        return repository.findById(id).filter(row -> java.util.Set.of("ADMIN", "STAFF", "TOUR_GUIDE")
                        .contains(java.util.Objects.toString(row.getActorType(), "")))
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Không tìm thấy thao tác quản trị"));
    }

    private static String groupOf(String entity) {
        return switch (entity.toUpperCase(java.util.Locale.ROOT)) {
            case "PRICE", "PAYMENT", "INVOICE" -> "FINANCE";
            case "USER", "ACCOUNT", "ROLE", "PERMISSION" -> "ACCOUNT";
            case "TOUR", "ITINERARY", "REVIEW" -> "CONTENT";
            default -> "OPERATIONS";
        };
    }
}
