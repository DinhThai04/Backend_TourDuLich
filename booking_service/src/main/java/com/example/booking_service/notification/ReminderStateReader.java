package com.example.booking_service.notification;

import com.example.booking_service.repository.httpclient.AuthInternalClient;
import com.example.booking_service.repository.httpclient.InvoiceInternalClient;
import com.example.booking_service.repository.httpclient.PaymentInternalClient;
import com.example.booking_service.repository.httpclient.TourInternalClient;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ReminderStateReader {
    public record State(int bookingId, int accountId, int departureId, String recipient, String name, String tour,
            String bookingStatus, LocalDateTime createdAt, LocalDateTime departureAt, int passengers,
            String reservationStatus, boolean reservationMatches, LocalDateTime deadline, LocalDateTime reminderOne, LocalDateTime reminderTwo,
            int paymentCount, int invoiceCount, int allPaidCount, boolean reconciliation,
            LocalDateTime paidAt, BigDecimal paymentAmount, BigDecimal invoiceAmount) {}

    private final JdbcTemplate jdbc;
    private final AuthInternalClient authClient;
    private final TourInternalClient tourClient;
    private final PaymentInternalClient paymentClient;
    private final InvoiceInternalClient invoiceClient;

    public ReminderStateReader(JdbcTemplate jdbc, AuthInternalClient authClient, TourInternalClient tourClient,
            PaymentInternalClient paymentClient, InvoiceInternalClient invoiceClient) {
        this.jdbc = jdbc;
        this.authClient = authClient;
        this.tourClient = tourClient;
        this.paymentClient = paymentClient;
        this.invoiceClient = invoiceClient;
    }

    public Optional<State> read(int id) {
        var bookingRows = jdbc.queryForList("SELECT id, account_id, status, created_at, tour_departure_id FROM booking WHERE id=?", id);
        if (bookingRows.isEmpty()) return Optional.empty();
        var booking = bookingRows.getFirst();
        int accountId = number(booking.get("account_id"));
        int departureId = number(booking.get("tour_departure_id"));

        String email, username;
        try {
            Map<String, Object> user = authClient.getUserById((long) accountId);
            email = (String) user.get("email");
            username = (String) user.get("username");
        } catch (Exception e) {
            return Optional.empty();
        }

        String tourTitle;
        LocalDateTime departureDate;
        String operationalStatus;
        try {
            Map<String, Object> departure = tourClient.getDeparture(departureId);
            tourTitle = departure.get("tour_id") != null ? "Tour #" + departure.get("tour_id") : "Tour";
            departureDate = date(departure.get("departure_date"));
            Object status = departure.get("operational_status");
            operationalStatus = status != null ? status.toString() : "OPEN";
        } catch (Exception e) {
            return Optional.empty();
        }

        if (!List.of("OPEN", "REPLACEMENT_ONLY").contains(operationalStatus)) {
            return Optional.empty();
        }

        var rows = jdbc.queryForList("""
            SELECT b.id, b.status, b.created_at,
              (SELECT MAX(c.applied_at) FROM booking_change c WHERE c.booking_id=b.id AND c.applied_at IS NOT NULL) AS last_change_at,
              r.status AS reservation_status, r.expires_at, r.departure_at AS reserved_departure_at, r.departure_id AS reserved_departure_id,
              r.reminder_one_at, r.reminder_two_at,
              (SELECT COUNT(*) FROM customer c WHERE c.booking_id=b.id) AS passengers
            FROM booking b
            LEFT JOIN booking_reservation r ON r.booking_id=b.id WHERE b.id=?
            """, id);
        if (rows.size() != 1) return Optional.empty();
        var r = rows.getFirst();

        int paymentCount = 0;
        int allPaidCount = 0;
        boolean reconciliation = false;
        LocalDateTime paymentAt = null;
        BigDecimal paymentAmount = null;
        try {
            Map<String, Object> payEv = paymentClient.getBookingPaymentEvidence(id);
            if (payEv != null) {
                if (Boolean.TRUE.equals(payEv.get("paidPayment"))) {
                    paymentCount = 1;
                    allPaidCount++;
                }
                if (Boolean.TRUE.equals(payEv.get("paidProvider"))) {
                    allPaidCount++;
                }
                reconciliation = Boolean.TRUE.equals(payEv.get("openReconciliation"));
            }
            if (paymentCount > 0) {
                Map<String, Object> pay = paymentClient.getPaymentStatus(id);
                if (pay != null && "PAID".equals(pay.get("status"))) {
                    paymentAt = date(pay.get("dayOfPay"));
                    paymentAmount = amount(pay.get("totalBookingTourExpense"));
                }
            }
        } catch (Exception e) {
            return Optional.empty();
        }

        int invoiceCount = 0;
        LocalDateTime invoiceAt = null;
        BigDecimal invoiceAmount = null;
        try {
            Map<String, Object> inv = invoiceClient.getInvoiceByBookingId(id);
            if (inv != null && "PAID".equals(inv.get("status"))) {
                invoiceCount = 1;
                allPaidCount++;
                invoiceAt = date(inv.get("dayOfPay"));
                invoiceAmount = amount(inv.get("totalBookingTourExpense"));
            }
        } catch (feign.FeignException.NotFound ignored) {
            // No invoice yet
        } catch (Exception e) {
            return Optional.empty();
        }

        LocalDateTime paid = later(paymentAt, invoiceAt);
        return Optional.of(new State(id, accountId, departureId,
                email, username, tourTitle, (String) r.get("status"),
                later(date(r.get("created_at")), date(r.get("last_change_at"))), departureDate, number(r.get("passengers")),
                (String) r.get("reservation_status"), r.get("reserved_departure_id") == null
                    || (number(r.get("reserved_departure_id")) == departureId
                        && departureDate != null && departureDate.equals(date(r.get("reserved_departure_at")))),
                date(r.get("expires_at")), date(r.get("reminder_one_at")), date(r.get("reminder_two_at")),
                paymentCount, invoiceCount, allPaidCount,
                reconciliation, paid, paymentAmount, invoiceAmount));
    }

    private static int number(Object value) { return ((Number) value).intValue(); }
    private static BigDecimal amount(Object value) { return value == null ? null : new BigDecimal(value.toString()); }
    static LocalDateTime date(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime date) return date;
        if (value instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        if (value instanceof String text && !text.isBlank()) return LocalDateTime.parse(text.trim());
        return null;
    }
    private static LocalDateTime later(LocalDateTime a, LocalDateTime b) {
        return a == null ? b : b == null ? a : a.isAfter(b) ? a : b;
    }
}
