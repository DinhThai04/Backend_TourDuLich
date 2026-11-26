package com.example.booking_service.reservation;

import com.example.booking_service.config.BookingMailOutboxPublisher;
import com.example.booking_service.notification.MailEventType;
import com.example.booking_service.repository.httpclient.AuthInternalClient;
import com.example.booking_service.repository.httpclient.TourInternalClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
public class BookingMailEvents {
    private final JdbcTemplate jdbc;
    private final BookingMailOutboxPublisher publisher;
    private final Clock clock;
    private final AuthInternalClient authClient;
    private final TourInternalClient tourClient;

    public BookingMailEvents(JdbcTemplate jdbc, BookingMailOutboxPublisher publisher, Clock clock,
                             AuthInternalClient authClient, TourInternalClient tourClient) {
        this.jdbc = jdbc; this.publisher = publisher; this.clock = clock;
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
            Object titleObj = departure.get("tour_title");
            tourTitle = (titleObj instanceof String s && !s.isBlank()) ? s : tourIdLabel(departure.get("tour_id"));
            departureDate = date(departure.get("departure_date"));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot fetch tour info for booking " + bookingId, e);
        }

        // Get passenger count
        int passengers = jdbc.queryForObject("SELECT COUNT(*) FROM customer WHERE booking_id=?", Integer.class, bookingId);

        String eventKey = "booking:" + bookingId + ":" + type.name().toLowerCase();
        String label = switch (type) {
            case BOOKING_CREATED -> "Đã giữ chỗ";
            case BOOKING_EXPIRED -> "Đã hết hạn giữ chỗ";
            case BOOKING_CANCELLED -> "Đã hủy đặt chỗ";
            default -> "Cập nhật đơn đặt tour";
        };
        String subject = "SGU Tour · " + label + " (#" + bookingId + ")";
        String html = "<p>Xin chào <b>" + escapeHtml(username) + "</b>,</p>"
                + "<p>Đơn hàng <b>#" + bookingId + "</b> (" + escapeHtml(tourTitle) + ") · " + label + ".</p>"
                + "<p>Số lượng khách: " + passengers + ". " + (holdUntil != null ? "Thanh toán trước " + holdUntil : "") + "</p>";
        String text = "Xin chào " + username + ",\nĐơn hàng #" + bookingId + " (" + tourTitle + ") · " + label
                + ".\nSố lượng khách: " + passengers + ".\n" + (holdUntil != null ? "Thanh toán trước " + holdUntil : "");
        String scheduledAt = LocalDateTime.now(clock).format(DateTimeFormatter.ISO_DATE_TIME);

        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("eventKey", eventKey);
        payload.put("eventType", type.name());
        payload.put("sourceService", "booking-service");
        payload.put("bookingId", bookingId);
        payload.put("accountId", accountId);
        payload.put("recipient", email);
        payload.put("subject", subject);
        payload.put("html", html);
        payload.put("text", text);
        payload.put("scheduledAt", scheduledAt);

        String payloadJson;
        try {
            payloadJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize mail payload for booking " + bookingId, e);
        }
        publisher.sendNow(payloadJson);
    }

    private static String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static LocalDateTime date(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime date) return date;
        if (value instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        if (value instanceof String s && !s.isBlank()) {
            return LocalDateTime.parse(s.contains(" ") && !s.contains("T") ? s.replace(' ', 'T') : s);
        }
        if (value instanceof java.util.List<?> list && list.size() >= 5) {
            int y = ((Number) list.get(0)).intValue();
            int m = ((Number) list.get(1)).intValue();
            int d = ((Number) list.get(2)).intValue();
            int h = ((Number) list.get(3)).intValue();
            int min = ((Number) list.get(4)).intValue();
            int sec = list.size() > 5 ? ((Number) list.get(5)).intValue() : 0;
            return LocalDateTime.of(y, m, d, h, min, sec);
        }
        throw new IllegalStateException("Unexpected date value: " + value.getClass().getName());
    }

    static String tourIdLabel(Object value) {
        if (value == null) return "Tour";
        return "Tour #" + value;
    }
}