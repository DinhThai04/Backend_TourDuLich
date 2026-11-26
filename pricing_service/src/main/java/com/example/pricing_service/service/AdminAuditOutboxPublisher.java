package com.example.pricing_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Relays committed administrative changes to the central audit with at-least-once delivery. */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.audit.outbox.enabled", havingValue = "true")
public class AdminAuditOutboxPublisher {
    private static final int BATCH_SIZE = 25;
    private static final int LEASE_SECONDS = 120;
    private static final int MAX_ATTEMPTS = 12;

    private final JdbcTemplate jdbc;

    @org.springframework.beans.factory.annotation.Value("${REPORTING_INTERNAL_KEY:}")
    private String reportingKey;
    @org.springframework.beans.factory.annotation.Value("${REPORTING_BASE_URL:http://reporting-service:8092/reporting}")
    private String reportingUrl;
    private final org.springframework.web.client.RestClient reporting = reportingClient();

    private static org.springframework.web.client.RestClient reportingClient() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(3000);
        return org.springframework.web.client.RestClient.builder().requestFactory(factory).build();
    }

    @Scheduled(fixedDelayString = "${app.audit.outbox.poll-interval-ms:1000}")
    public void publishPendingEvents() {
        List<Map<String, Object>> candidates = jdbc.queryForList("""
                SELECT id, payload, status
                FROM pricing_outbox
                WHERE event_type = 'admin.audit.v1'
                  AND status IN ('PENDING', 'PROCESSING')
                  AND available_at <= CURRENT_TIMESTAMP(6)
                ORDER BY id
                LIMIT ?
                """, BATCH_SIZE);

        candidates.forEach(this::deliverIfClaimed);
    }

    private void deliverIfClaimed(Map<String, Object> candidate) {
        long rowId = number(candidate, "id").longValue();
        String previousStatus = String.valueOf(candidate.get("status"));

        // Compare the lease against the database clock so JDBC and database time zones cannot drift.
        int claimed = jdbc.update("""
                        UPDATE pricing_outbox
                        SET status = 'PROCESSING', available_at = TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(6)),
                            attempts = attempts + 1
                        WHERE id = ? AND status = ? AND available_at <= CURRENT_TIMESTAMP(6)
                        """,
                LEASE_SECONDS, rowId, previousStatus);
        if (claimed != 1) return;

        int attempt = number(jdbc.queryForObject(
                "SELECT attempts FROM pricing_outbox WHERE id = ?", Integer.class, rowId)).intValue();
        try {
            reporting.post().uri(reportingUrl + "/internal/reports/admin-audit/events")
                    .header("X-Internal-Reporting-Key", reportingKey)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(String.valueOf(candidate.get("payload"))).retrieve().toBodilessEntity();
            jdbc.update("""
                            UPDATE pricing_outbox
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
                        UPDATE pricing_outbox
                        SET status = ?, available_at = TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(6)), last_error = ?
                        WHERE id = ? AND status = 'PROCESSING'
                        """, nextStatus, retryDelaySeconds,
                failure.getClass().getSimpleName(), rowId);
        log.warn("Administrative audit outbox delivery failed event row {}; attempt {}; status {}",
                rowId, attempt, nextStatus);
    }

    private static Number number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Number number) return number;
        throw new IllegalStateException("Invalid Administrative audit outbox row");
    }

    private static Number number(Object value) {
        if (value instanceof Number number) return number;
        throw new IllegalStateException("Invalid Administrative audit outbox attempt count");
    }


}
