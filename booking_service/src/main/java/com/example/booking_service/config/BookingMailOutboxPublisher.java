package com.example.booking_service.config;

import com.example.booking_service.config.NotificationMailClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Delivers durable Booking-owned mail-requested events to Notification with at-least-once semantics. */
@Component
@Slf4j
public class BookingMailOutboxPublisher {
    private static final int BATCH_SIZE = 25;
    private static final int LEASE_SECONDS = 120;
    private static final int MAX_ATTEMPTS = 12;

    private final JdbcTemplate jdbc;
    private final NotificationMailClient notificationClient;
    private final LocalDateTime dispatchCreatedFrom;

    public BookingMailOutboxPublisher(JdbcTemplate jdbc, NotificationMailClient notificationClient,
            @Value("${app.booking.mail.outbox.dispatch-created-from:1970-01-01T00:00:00}") String dispatchCreatedFrom) {
        this.jdbc = jdbc;
        this.notificationClient = notificationClient;
        this.dispatchCreatedFrom = LocalDateTime.parse(dispatchCreatedFrom);
    }

    @Scheduled(fixedDelayString = "${app.booking.outbox.poll-interval-ms:1000}")
    public void publishPendingEvents() {
        List<Map<String, Object>> candidates = jdbc.queryForList("""
                SELECT id, payload, status, attempts
                FROM booking_outbox
                WHERE event_type = 'booking.mail.requested.v1'
                  AND status IN ('PENDING', 'PROCESSING')
                  AND available_at <= CURRENT_TIMESTAMP(6) AND created_at >= ?
                ORDER BY id
                LIMIT ?
                """, dispatchCreatedFrom, BATCH_SIZE);

        candidates.forEach(this::deliverIfClaimed);
    }

    private void deliverIfClaimed(Map<String, Object> candidate) {
        long rowId = number(candidate, "id").longValue();
        String payloadJson = String.valueOf(candidate.get("payload"));
        String previousStatus = String.valueOf(candidate.get("status"));
        int previousAttempts = number(candidate, "attempts").intValue();

        int claimed = jdbc.update("""
                        UPDATE booking_outbox
                        SET status = 'PROCESSING', available_at = TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(6)),
                            attempts = attempts + 1
                        WHERE id = ? AND status = ? AND attempts = ? AND available_at <= CURRENT_TIMESTAMP(6)
                        """,
                LEASE_SECONDS, rowId, previousStatus, previousAttempts);
        if (claimed != 1) return;

        int attempt = previousAttempts + 1;
        try {
            notificationClient.sendBookingMail(payloadJson);
            jdbc.update("""
                            UPDATE booking_outbox
                            SET status = 'PUBLISHED', published_at = NOW(6), last_error = NULL
                            WHERE id = ? AND status = 'PROCESSING' AND attempts = ?
                            """, rowId, attempt);
        } catch (RuntimeException deliveryFailure) {
            recordFailure(rowId, attempt, deliveryFailure);
        }
    }

    private void recordFailure(long rowId, int attempt, RuntimeException failure) {
        boolean exhausted = attempt >= MAX_ATTEMPTS;
        long retryDelaySeconds = Math.min(300L, 1L << Math.min(attempt, 8));
        String nextStatus = exhausted ? "DEAD_LETTER" : "PENDING";
        jdbc.update("""
                        UPDATE booking_outbox
                        SET status = ?, available_at = TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(6)), last_error = ?
                        WHERE id = ? AND status = 'PROCESSING' AND attempts = ?
                        """, nextStatus, retryDelaySeconds, failure.getClass().getSimpleName(), rowId, attempt);
        log.warn("Booking mail outbox delivery failed event row {}; attempt {}; status {}",
                rowId, attempt, nextStatus);
    }

    private static Number number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Number number) return number;
        throw new IllegalStateException("Invalid Booking mail outbox row");
    }

    /**
     * Persists the mail request in Booking's transaction. The scheduled publisher then calls
     * Notification with retries, so a short Notification outage cannot lose booking mail.
     */
    public void sendNow(String payloadJson) {
        String aggregateId;
        try {
            var payload = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payloadJson);
            aggregateId = payload.path("bookingId").asText("0");
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalidPayload) {
            throw new IllegalArgumentException("Booking mail payload is invalid", invalidPayload);
        }
        jdbc.update("""
                INSERT INTO booking_outbox(event_id,event_type,aggregate_type,aggregate_id,
                    aggregate_version,payload,status,attempts,available_at,created_at)
                VALUES (?,'booking.mail.requested.v1','Booking',?,1,?,'PENDING',0,
                    CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
                """, UUID.randomUUID().toString(), aggregateId, payloadJson);
    }
    public boolean enqueueOnce(int bookingId, String eventKey, String payloadJson) {
        String eventId = UUID.nameUUIDFromBytes(eventKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        return jdbc.update("""
                INSERT IGNORE INTO booking_outbox(event_id,event_type,aggregate_type,aggregate_id,
                    aggregate_version,payload,status,attempts,available_at,created_at)
                VALUES (?,'booking.mail.requested.v1','Booking',?,1,?,'PENDING',0,
                    CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
                """, eventId, Integer.toString(bookingId), payloadJson) == 1;
    }

}