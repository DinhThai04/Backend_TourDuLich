package com.example.booking_service;

import com.example.booking_service.repository.httpclient.InvoiceInternalClient;
import com.example.booking_service.repository.httpclient.PaymentInternalClient;
import com.example.booking_service.repository.httpclient.TourInternalClient;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public final class TestInternalClients {
    private TestInternalClients() {}

    public static TourInternalClient tour(JdbcTemplate jdbc) {
        TourInternalClient client = mock(TourInternalClient.class);
        when(client.getDeparture(anyInt())).thenAnswer(inv -> {
            int id = inv.getArgument(0, Integer.class);
            var rows = jdbc.queryForList("SELECT * FROM tour_departure WHERE id=?", id);
            return rows.isEmpty() ? null : normalized(rows.getFirst());
        });
        when(client.getDeparturesByTour(anyInt())).thenAnswer(inv -> {
            int tourId = inv.getArgument(0, Integer.class);
            return jdbc.queryForList("SELECT * FROM tour_departure WHERE tour_id=?", tourId)
                    .stream().map(TestInternalClients::normalized).toList();
        });
        when(client.holdSeats(anyInt(), any())).thenAnswer(inv -> {
            int id = inv.getArgument(0, Integer.class);
            var req = inv.getArgument(1, TourInternalClient.HoldSeatsRequest.class);
            int updated = jdbc.update("UPDATE tour_departure SET available_seats=available_seats-?,version=version+1 WHERE id=? AND available_seats>=?",
                    req.seats(), id, req.seats());
            if (updated != 1) throw new IllegalStateException("Insufficient seats");
            return Map.of("success", true);
        });
        when(client.releaseHold(anyInt(), anyInt())).thenAnswer(inv -> {
            int id = inv.getArgument(0, Integer.class);
            int seats = inv.getArgument(1, Integer.class);
            jdbc.update("UPDATE tour_departure SET available_seats=LEAST(COALESCE(capacity,32767),available_seats+?),version=version+1 WHERE id=?", seats, id);
            return Map.of("success", true);
        });
        doAnswer(inv -> {
            int id = inv.getArgument(0, Integer.class);
            jdbc.update("UPDATE tour_departure SET version=version+1 WHERE id=?", id);
            return null;
        }).when(client).incrementVersion(anyInt());
        doAnswer(inv -> {
            int id = inv.getArgument(0, Integer.class);
            String status = inv.getArgument(1, String.class);
            jdbc.update("UPDATE tour_departure SET operational_status=?,version=version+1 WHERE id=?", status, id);
            return null;
        }).when(client).updateOperationalStatus(anyInt(), anyString());
        return client;
    }

    public static PaymentInternalClient payment(JdbcTemplate jdbc) {
        return payment(jdbc, java.time.Clock.system(ZoneId.of("Asia/Ho_Chi_Minh")));
    }

    public static PaymentInternalClient payment(JdbcTemplate jdbc, java.time.Clock clock) {
        PaymentInternalClient client = mock(PaymentInternalClient.class);
        when(client.getBookingPaymentEvidence(anyInt())).thenAnswer(inv -> {
            int bId = inv.getArgument(0, Integer.class);
            LocalDateTime grace = LocalDateTime.now(clock).minusMinutes(5);
            int paidPay = count(jdbc, "SELECT COUNT(*) FROM payment WHERE booking_id=? AND status='PAID'", bId);
            int paidVn = count(jdbc, "SELECT COUNT(*) FROM vnpay_transaction WHERE booking_id=? AND status='PAID'", bId);
            int paidMo = count(jdbc, "SELECT COUNT(*) FROM momo_transaction WHERE booking_id=? AND status='PAID'", bId);
            int openRec = count(jdbc, "SELECT COUNT(*) FROM payment_reconciliation WHERE booking_id=? AND status='OPEN'", bId);
            int pendVn = count(jdbc, "SELECT COUNT(*) FROM vnpay_transaction WHERE booking_id=? AND status='PENDING' AND expires_at>?", bId, grace);
            int pendMo = count(jdbc, "SELECT COUNT(*) FROM momo_transaction WHERE booking_id=? AND status='PENDING' AND expires_at>?", bId, grace);
            return Map.of(
                    "paidPayment", paidPay > 0,
                    "paidProvider", (paidVn + paidMo) > 0,
                    "openReconciliation", openRec > 0,
                    "inFlight", (pendVn + pendMo) > 0,
                    "paidEvidence", (paidPay + paidVn + paidMo + openRec) > 0,
                    "evidenceCount", paidPay + paidVn + paidMo + openRec + pendVn + pendMo
            );
        });
        when(client.getPaymentStatus(anyInt())).thenAnswer(inv -> {
            int bId = inv.getArgument(0, Integer.class);
            var rows = jdbc.queryForList("SELECT * FROM payment WHERE booking_id=? ORDER BY id DESC LIMIT 1", bId);
            if (rows.isEmpty()) return null;
            var r = normalized(rows.getFirst());
            Map<String, Object> out = new HashMap<>();
            out.put("status", r.get("status"));
            out.put("accountId", r.get("account_id"));
            out.put("dayOfPay", r.get("day_of_pay"));
            out.put("totalBookingTourExpense", r.get("total_booking_tour_expense"));
            return out;
        });
        when(client.refund(anyInt(), anyLong(), anyString())).thenAnswer(inv -> {
            int bId = inv.getArgument(0, Integer.class);
            jdbc.update("UPDATE payment SET status='REFUNDED' WHERE booking_id=? AND status='PAID'", bId);
            jdbc.update("UPDATE vnpay_transaction SET status='REFUNDED' WHERE booking_id=? AND status='PAID'", bId);
            jdbc.update("UPDATE momo_transaction SET status='REFUNDED' WHERE booking_id=? AND status='PAID'", bId);
            jdbc.update("UPDATE invoice SET status='REFUNDED' WHERE booking_id=? AND status='PAID'", bId);
            return Map.of("success", true);
        });
        return client;
    }

    public static InvoiceInternalClient invoice(JdbcTemplate jdbc) {
        InvoiceInternalClient client = mock(InvoiceInternalClient.class);
        when(client.getInvoiceByBookingId(anyInt())).thenAnswer(inv -> {
            int bId = inv.getArgument(0, Integer.class);
            var rows = jdbc.queryForList("SELECT * FROM invoice WHERE booking_id=? ORDER BY id DESC LIMIT 1", bId);
            if (rows.isEmpty()) return null;
            var r = normalized(rows.getFirst());
            Map<String, Object> out = new HashMap<>();
            out.put("status", r.get("status"));
            out.put("accountId", r.get("account_id"));
            out.put("dayOfPay", r.get("day_of_pay"));
            out.put("totalBookingTourExpense", r.get("total_booking_tour_expense"));
            return out;
        });
        return client;
    }

    private static int count(JdbcTemplate jdbc, String sql, Object... args) {
        try {
            Integer n = jdbc.queryForObject(sql, Integer.class, args);
            return n != null ? n : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static Map<String, Object> normalized(Map<String, Object> src) {
        Map<String, Object> out = new HashMap<>();
        src.forEach((k, v) -> out.put(k.toLowerCase(Locale.ROOT), v));
        return out;
    }
}
