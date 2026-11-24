package com.example.payment_service;

import com.example.payment_service.notification.PaymentMailOutboxWriter;
import com.example.payment_service.repository.httpclient.AuthInternalClient;
import com.example.payment_service.repository.httpclient.PricingInternalClient;
import com.example.payment_service.repository.httpclient.TourInternalClient;
import com.example.payment_service.service.VnPayOrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Test adapters that model the service API responses from each fixture's local tables. */
public final class TestServiceClients {
    private TestServiceClients() {}

    public static AuthInternalClient auth(JdbcTemplate jdbc) {
        AuthInternalClient client = mock(AuthInternalClient.class);
        when(client.getUserById(anyLong())).thenAnswer(invocation -> {
            Object[] args = {invocation.getArgument(0, Long.class)};
            var rows = jdbc.queryForList("SELECT id,username,email FROM users WHERE id=?", args);
            if (rows.isEmpty()) return Map.of();
            Map<String, Object> user = normalized(rows.getFirst());
            user.put("status", "ACTIVE");
            return user;
        });
        return client;
    }

    public static TourInternalClient tour(JdbcTemplate jdbc) {
        TourInternalClient client = mock(TourInternalClient.class);
        when(client.getDeparture(anyInt())).thenAnswer(invocation -> {
            Object[] args = {invocation.getArgument(0, Integer.class)};
            var rows = jdbc.queryForList("SELECT * FROM tour_departure WHERE id=?", args);
            return rows.isEmpty() ? Map.of() : normalized(rows.getFirst());
        });
        when(client.getCapacity(anyInt())).thenAnswer(invocation -> {
            Object[] args = {invocation.getArgument(0, Integer.class)};
            var rows = jdbc.queryForList("SELECT * FROM tour_departure WHERE id=?", args);
            return rows.isEmpty() ? Map.of() : normalized(rows.getFirst());
        });
        when(client.holdSeats(anyInt(), org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            int depId = invocation.getArgument(0, Integer.class);
            var req = invocation.getArgument(1, TourInternalClient.HoldSeatsRequest.class);
            jdbc.update("UPDATE tour_departure SET available_seats=available_seats-?,version=version+1 WHERE id=?", req.seats(), depId);
            return Map.of("success", true);
        });
        return client;
    }

    public static PricingInternalClient pricing(JdbcTemplate jdbc) {
        PricingInternalClient client = mock(PricingInternalClient.class);
        when(client.getPriceById(anyLong())).thenAnswer(invocation -> {
            Object[] args = {invocation.getArgument(0, Long.class)};
            var rows = jdbc.queryForList("SELECT * FROM prices WHERE id=?", args);
            if (rows.isEmpty()) return Map.of();
            Map<String, Object> price = new HashMap<>();
            Map<String, Object> stored = normalized(rows.getFirst());
            price.put("adultPrice", stored.get("adult_price"));
            price.put("childPrice", stored.get("child_price"));
            price.put("toddlerPrice", stored.get("toddler_price"));
            price.put("infantPrice", stored.get("infant_price"));
            price.put("singleSupplementPrice", stored.get("single_supplement_price"));
            return price;
        });
        return client;
    }

    public static com.example.payment_service.repository.httpclient.BookingInternalClient booking(JdbcTemplate jdbc) {
        var client = mock(com.example.payment_service.repository.httpclient.BookingInternalClient.class);
        when(client.getBooking(anyInt())).thenAnswer(invocation -> {
            int id = invocation.getArgument(0, Integer.class);
            jdbc.queryForList("SELECT id FROM booking WHERE id=?", id);
            var rows = jdbc.queryForList("SELECT * FROM booking WHERE id=? FOR UPDATE", id);
            return rows.isEmpty() ? null : normalized(rows.getFirst());
        });
        when(client.getBookingReservation(anyInt())).thenAnswer(invocation -> {
            int id = invocation.getArgument(0, Integer.class);
            var rows = jdbc.queryForList("SELECT * FROM booking_reservation WHERE booking_id=?", id);
            return rows.isEmpty() ? null : normalized(rows.getFirst());
        });
        when(client.getBookingCustomers(anyInt())).thenAnswer(invocation -> {
            int id = invocation.getArgument(0, Integer.class);
            return jdbc.queryForList("SELECT booking_type FROM customer WHERE booking_id=?", id).stream()
                    .map(TestServiceClients::normalized).toList();
        });
        when(client.getBookingPriceSnapshot(anyInt())).thenAnswer(invocation -> {
            int id = invocation.getArgument(0, Integer.class);
            var rows = jdbc.queryForList("SELECT * FROM booking_price_snapshot WHERE booking_id=?", id);
            return rows.isEmpty() ? null : normalized(rows.getFirst());
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            int id = invocation.getArgument(0, Integer.class);
            jdbc.update("UPDATE booking SET status='CONFIRMED' WHERE id=? AND status='PENDING_PAYMENT'", id);
            jdbc.update("UPDATE booking_reservation SET status='PAID' WHERE booking_id=?", id);
            return null;
        }).when(client).markBookingPaid(anyInt());
        org.mockito.Mockito.doAnswer(invocation -> {
            int id = invocation.getArgument(0, Integer.class);
            var holds = jdbc.queryForList("SELECT departure_id, seats, status FROM booking_reservation WHERE booking_id=?", id);
            if (!holds.isEmpty() && "CHECKOUT".equals(holds.getFirst().get("STATUS"))) {
                int depId = ((Number) holds.getFirst().get("DEPARTURE_ID")).intValue();
                int seats = ((Number) holds.getFirst().get("SEATS")).intValue();
                if (seats > 0) {
                    jdbc.update("UPDATE tour_departure SET available_seats=LEAST(COALESCE(capacity,32767),available_seats+?),version=version+1 WHERE id=?", seats, depId);
                }
            }
            jdbc.update("UPDATE booking SET status='EXPIRED' WHERE id=? AND status IN ('PENDING_PAYMENT','CONFIRMED')", id);
            jdbc.update("UPDATE booking_reservation SET status='EXPIRED',cancel_reason='CHECKOUT_FAILED' WHERE booking_id=? AND status='CHECKOUT'", id);
            return null;
        }).when(client).expireCheckout(anyInt());
        return client;
    }

    public static com.example.payment_service.outbox.OutboxPublisher outbox(JdbcTemplate jdbc) {
        return new com.example.payment_service.outbox.OutboxPublisher(
                mock(org.springframework.amqp.rabbit.core.RabbitTemplate.class), jdbc);
    }

    public static PaymentMailOutboxWriter mailWriter(JdbcTemplate jdbc, ObjectMapper mapper) {
        return new PaymentMailOutboxWriter(jdbc, outbox(jdbc), auth(jdbc));
    }

    public static VnPayOrderService vnpay(JdbcTemplate jdbc, Clock clock, ObjectMapper mapper) {
        var tour = tour(jdbc);
        var outbox = outbox(jdbc);
        var writer = new PaymentMailOutboxWriter(jdbc, outbox, auth(jdbc));
        return new VnPayOrderService(jdbc, writer, clock, tour, pricing(jdbc), booking(jdbc), outbox);
    }

    private static Map<String, Object> normalized(Map<String, Object> source) {
        Map<String, Object> result = new HashMap<>();
        source.forEach((key, value) -> result.put(key.toLowerCase(Locale.ROOT), value));
        return result;
    }
}
