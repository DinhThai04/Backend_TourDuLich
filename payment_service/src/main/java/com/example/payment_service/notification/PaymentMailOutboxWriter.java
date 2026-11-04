package com.example.payment_service.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;

/** Same datasource/transaction as VNPay settlement. No HTTP or SMTP inside this writer. */
@Component
@Slf4j
public class PaymentMailOutboxWriter {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public PaymentMailOutboxWriter(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }
    public void receipt(int bookingId, String ref, long amount, LocalDateTime paidAt, LocalDateTime now, String provider) {
        write(bookingId, "PAYMENT_SUCCEEDED", "booking:" + bookingId + ":paid:" + provider + ":" + ref, ref, amount, paidAt, now);
    }
    public void expired(int bookingId, LocalDateTime now) {
        write(bookingId, "BOOKING_EXPIRED", "booking:" + bookingId + ":booking_expired", null, null, null, now);
    }
    private void write(int bookingId, String type, String key, String ref, Long amount, LocalDateTime paidAt, LocalDateTime now) {
        var rows = jdbc.queryForList("""
            SELECT b.account_id,u.username,u.email,t.tour_title,d.id AS departure_id,d.departure_date,
                (SELECT COUNT(*) FROM customer c WHERE c.booking_id=b.id) AS passengers
            FROM booking b JOIN users u ON u.id=b.account_id JOIN tour_departure d ON d.id=b.tour_departure_id
            JOIN tour t ON t.id=d.tour_id WHERE b.id=?
            """, bookingId);
        // Mail is best-effort: an orphaned booking/user row must not roll back a settlement that
        // already moved money and seats.
        if (rows.isEmpty()) {
            log.warn("Skipping {} mail for booking {}: booking, user or tour row is missing", type, bookingId);
            return;
        }
        var row = rows.getFirst();
        var payload = new LinkedHashMap<String,Object>();
        payload.put("version", 1); payload.put("customerName", row.get("username")); payload.put("tourTitle", row.get("tour_title"));
        payload.put("departureId", row.get("departure_id")); payload.put("departureAt", date(row.get("departure_date")));
        payload.put("passengerCount", row.get("passengers")); payload.put("amountVnd", amount);
        payload.put("transactionRef", ref); payload.put("paidAt", paidAt);
        try {
            jdbc.update("""
                INSERT INTO mail_outbox (event_key,event_type,source_service,booking_id,account_id,recipient,payload,
                    scheduled_at,status,next_attempt_at,created_at,updated_at)
                VALUES (?,?,'payment_service',?,?,?,?,?,'PENDING',?,?,?)
                """, key, type, bookingId, row.get("account_id"), row.get("email"), mapper.writeValueAsString(payload), now, now, now, now);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("Cannot encode payment mail", e); }
        // Do not suppress SQL errors: roll back settlement instead of losing the event.
    }
    private static LocalDateTime date(Object value) {
        return value instanceof LocalDateTime date ? date : ((java.sql.Timestamp)value).toLocalDateTime();
    }
}
