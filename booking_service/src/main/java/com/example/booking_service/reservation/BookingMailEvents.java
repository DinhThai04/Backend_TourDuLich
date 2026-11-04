package com.example.booking_service.reservation;

import com.example.booking_service.notification.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.LocalDateTime;

@Component
public class BookingMailEvents {
    private final JdbcTemplate jdbc;
    private final MailOutboxService outbox;
    private final Clock clock;
    public BookingMailEvents(JdbcTemplate jdbc, MailOutboxService outbox, Clock clock) {
        this.jdbc = jdbc; this.outbox = outbox; this.clock = clock;
    }
    public void enqueue(int bookingId, MailEventType type, LocalDateTime holdUntil) {
        var row = jdbc.queryForMap("""
            SELECT b.account_id,u.email,u.username,t.tour_title,d.id AS departure_id,d.departure_date,
                (SELECT COUNT(*) FROM customer c WHERE c.booking_id=b.id) AS passengers
            FROM booking b JOIN users u ON u.id=b.account_id JOIN tour_departure d ON d.id=b.tour_departure_id
            JOIN tour t ON t.id=d.tour_id WHERE b.id=?
            """, bookingId);
        var payload = new MailPayload(1, String.valueOf(row.get("username")), String.valueOf(row.get("tour_title")),
                ((Number)row.get("departure_id")).intValue(), date(row.get("departure_date")),
                ((Number)row.get("passengers")).intValue(), null, null, null, holdUntil, null);
        outbox.enqueue("booking:" + bookingId + ":" + type.name().toLowerCase(), type,
                "booking_service", bookingId, ((Number)row.get("account_id")).intValue(),
                String.valueOf(row.get("email")), payload, LocalDateTime.now(clock));
    }
    static LocalDateTime date(Object value) {
        return value instanceof LocalDateTime date ? date : ((java.sql.Timestamp)value).toLocalDateTime();
    }
}
