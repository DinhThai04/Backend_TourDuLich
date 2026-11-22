package com.example.booking_service.reservation;

import com.example.booking_service.notification.*;
import com.example.booking_service.repository.httpclient.AuthInternalClient;
import com.example.booking_service.repository.httpclient.TourInternalClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;

@Component
public class BookingMailEvents {
    private final JdbcTemplate jdbc;
    private final MailOutboxService outbox;
    private final Clock clock;
    private final AuthInternalClient authClient;
    private final TourInternalClient tourClient;

    public BookingMailEvents(JdbcTemplate jdbc, MailOutboxService outbox, Clock clock,
                             AuthInternalClient authClient, TourInternalClient tourClient) {
        this.jdbc = jdbc; this.outbox = outbox; this.clock = clock;
        this.authClient = authClient; this.tourClient = tourClient;
    }
    public void enqueue(int bookingId, MailEventType type, LocalDateTime holdUntil) {
        // Get booking basic info
        var booking = jdbc.queryForMap("SELECT account_id, tour_departure_id FROM booking WHERE id=?", bookingId);
        int accountId = ((Number) booking.get("account_id")).intValue();
        int departureId = ((Number) booking.get("tour_departure_id")).intValue();

        // Get user info via Auth internal API
        String email, username;
        try {
            Map<String, Object> user = authClient.getUserById((long) accountId);
            email = (String) user.get("email");
            username = (String) user.get("username");
        } catch (Exception e) {
            throw new IllegalStateException("Cannot fetch user info for booking " + bookingId, e);
        }

        // Get tour info via Tour internal API
        String tourTitle;
        LocalDateTime departureDate;
        try {
            Map<String, Object> departure = tourClient.getDeparture(departureId);
            tourTitle = (String) departure.get("tour_id") != null ? "Tour #" + departure.get("tour_id") : "Tour";
            Object depDate = departure.get("departure_date");
            departureDate = depDate instanceof LocalDateTime ? (LocalDateTime) depDate : ((java.sql.Timestamp) depDate).toLocalDateTime();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot fetch tour info for booking " + bookingId, e);
        }

        // Get passenger count
        int passengers = jdbc.queryForObject("SELECT COUNT(*) FROM customer WHERE booking_id=?", Integer.class, bookingId);

        var payload = new MailPayload(1, username, tourTitle,
                departureId, departureDate,
                passengers, null, null, null, holdUntil, null);
        outbox.enqueue("booking:" + bookingId + ":" + type.name().toLowerCase(), type,
                "booking_service", bookingId, accountId,
                email, payload, LocalDateTime.now(clock));
    }
    static LocalDateTime date(Object value) {
        return value instanceof LocalDateTime date ? date : ((java.sql.Timestamp)value).toLocalDateTime();
    }
}
