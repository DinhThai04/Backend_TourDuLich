package com.example.auth_service.service;

import com.example.auth_service.repository.httpclient.FileInternalClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Delivers Auth-owned lifecycle events to File with at-least-once semantics. */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.auth.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class AuthOutboxPublisher {
    private static final String ACCOUNT_DELETED = "auth.account.deleted.v1";
    private static final int BATCH_SIZE = 25;
    private static final int LEASE_SECONDS = 120;
    private static final int MAX_ATTEMPTS = 12;

    private final JdbcTemplate jdbc;
    private final FileInternalClient fileClient;

    @Scheduled(fixedDelayString = "${app.auth.outbox.poll-interval-ms:1000}")
    public void publishPendingEvents() {
        LocalDateTime now = databaseNow();
        List<Map<String, Object>> candidates = jdbc.queryForList("""
                SELECT id, event_id, aggregate_id, status, available_at
                FROM auth_outbox
                WHERE event_type = ?
                  AND status IN ('PENDING', 'PROCESSING')
                  AND available_at <= ?
                ORDER BY id
                LIMIT ?
                """, ACCOUNT_DELETED, Timestamp.valueOf(now), BATCH_SIZE);

        candidates.forEach(this::deliverIfClaimed);
    }

    private void deliverIfClaimed(Map<String, Object> candidate) {
        long rowId = number(candidate, "id").longValue();
        String eventId = String.valueOf(candidate.get("event_id"));
        String accountId = String.valueOf(candidate.get("aggregate_id"));
        String previousStatus = String.valueOf(candidate.get("status"));
        LocalDateTime availableAt = localDateTime(candidate.get("available_at"));

        int claimed = jdbc.update("""
                        UPDATE auth_outbox
                        SET status = 'PROCESSING', available_at = ?, attempts = attempts + 1
                        WHERE id = ? AND status = ? AND available_at = ?
                        """,
                Timestamp.valueOf(databaseNow().plusSeconds(LEASE_SECONDS)), rowId,
                previousStatus, Timestamp.valueOf(availableAt));
        if (claimed != 1) return;

        int attempt = number(jdbc.queryForObject(
                "SELECT attempts FROM auth_outbox WHERE id = ?", Integer.class, rowId)).intValue();
        try {
            fileClient.detachAccountMedia(accountId, eventId);
            jdbc.update("""
                            UPDATE auth_outbox
                            SET status = 'PUBLISHED', published_at = NOW(6), last_error = NULL
                            WHERE id = ? AND status = 'PROCESSING'
                            """, rowId);
        } catch (RuntimeException deliveryFailure) {
            recordFailure(rowId, attempt, deliveryFailure);
        }
    }

    private void recordFailure(long rowId, int attempt, RuntimeException failure) {
        boolean exhausted = attempt >= MAX_ATTEMPTS;
        long retryDelaySeconds = Math.min(300L, 1L << Math.min(attempt, 8));
        String nextStatus = exhausted ? "DEAD_LETTER" : "PENDING";
        jdbc.update("""
                        UPDATE auth_outbox
                        SET status = ?, available_at = ?, last_error = ?
                        WHERE id = ? AND status = 'PROCESSING'
                        """,
                nextStatus, Timestamp.valueOf(databaseNow().plusSeconds(retryDelaySeconds)),
                failure.getClass().getSimpleName(), rowId);
        log.warn("Auth outbox delivery failed event row {}; attempt {}; status {}",
                rowId, attempt, nextStatus);
    }

    private LocalDateTime databaseNow() {
        return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", LocalDateTime.class);
    }

    private static Number number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Number number) return number;
        throw new IllegalStateException("Invalid Auth outbox row");
    }

    private static Number number(Object value) {
        if (value instanceof Number number) return number;
        throw new IllegalStateException("Invalid Auth outbox attempt count");
    }

    private static LocalDateTime localDateTime(Object value) {
        if (value instanceof LocalDateTime dateTime) return dateTime;
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime();
        throw new IllegalStateException("Invalid Auth outbox availability timestamp");
    }
}
