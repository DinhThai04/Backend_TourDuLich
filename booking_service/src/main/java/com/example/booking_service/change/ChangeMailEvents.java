package com.example.booking_service.change;

import com.example.booking_service.notification.MailEventType;
import com.example.booking_service.notification.MailOutboxService;
import com.example.booking_service.notification.MailPayload;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import static com.example.booking_service.change.ChangeLedger.date;
import static com.example.booking_service.change.ChangeLedger.n;
import static com.example.booking_service.change.ChangeLedger.v;

@Component
public class ChangeMailEvents {

    private static final String EVENT_SOURCE = "booking_service";
    private static final DateTimeFormatter DEADLINE_FORMAT = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");

    private final JdbcTemplate jdbc;
    private final MailOutboxService outbox;
    private final Clock clock;

    public ChangeMailEvents(JdbcTemplate jdbc, MailOutboxService outbox, Clock clock) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.clock = clock;
    }

    public void enqueue(Map<String, Object> change) {
        int booking = n(change, "booking_id");
        var contact = jdbc.queryForMap("""
                SELECT u.username,u.email,t.tour_title FROM booking b JOIN users u ON u.id=b.account_id
                JOIN tour_departure d ON d.id=b.tour_departure_id JOIN tour t ON t.id=d.tour_id WHERE b.id=?
                """, booking);

        String explanation = String.valueOf(change.get("reason"));
        if (change.get("shop_change_id") != null) {
            var shopChange = jdbc.queryForMap(
                    "SELECT response_deadline FROM departure_change WHERE id=?", change.get("shop_change_id"));
            explanation += " Hạn phản hồi trực tuyến: "
                    + date(shopChange.get("response_deadline")).format(DEADLINE_FORMAT)
                    + " (giờ Việt Nam). Quá hạn vui lòng liên hệ shop để được hỗ trợ.";
        }

        var payload = new MailPayload(
                1,
                String.valueOf(contact.get("username")),
                String.valueOf(contact.get("tour_title")),
                n(change, "target_departure_id"),
                date(change.get("target_departure_at")),
                jdbc.queryForObject("SELECT COUNT(*) FROM customer WHERE booking_id=?", Integer.class, booking),
                null,
                null,
                null,
                null,
                null,
                null,
                ((Number) change.get("id")).longValue(),
                date(change.get("source_departure_at")),
                explanation,
                String.valueOf(change.get("status")),
                (String) change.get("refund_reference"));

        String idempotencyKey = "booking:" + booking + ":change:" + change.get("id") + ":"
                + change.get("status") + ":" + v(change);
        outbox.enqueue(
                idempotencyKey,
                MailEventType.BOOKING_CHANGE_UPDATED,
                EVENT_SOURCE,
                booking,
                n(change, "account_id"),
                String.valueOf(contact.get("email")),
                payload,
                LocalDateTime.now(clock));
    }
}
