package com.example.booking_service.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

/** Read-only view of current facts; no customer data is returned by the internal dispatch API. */
public class ReminderStateReader {
    public record State(int bookingId, int accountId, int departureId, String recipient, String name, String tour,
            String bookingStatus, LocalDateTime createdAt, LocalDateTime departureAt, int passengers,
            String reservationStatus, boolean reservationMatches, LocalDateTime deadline, LocalDateTime reminderOne, LocalDateTime reminderTwo,
            int paymentCount, int invoiceCount, int allPaidCount, boolean reconciliation,
            LocalDateTime paidAt, BigDecimal paymentAmount, BigDecimal invoiceAmount) {}
    private final JdbcTemplate jdbc;
    public ReminderStateReader(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Optional<State> read(int id) {
        var rows = jdbc.queryForList("""
            SELECT b.id,b.account_id,b.status,b.created_at,d.id AS departure_id,d.departure_date,
              (SELECT MAX(c.applied_at) FROM booking_change c WHERE c.booking_id=b.id AND c.applied_at IS NOT NULL) AS last_change_at,
              u.email,u.username,t.tour_title,r.status AS reservation_status,r.expires_at,r.departure_at AS reserved_departure_at,r.departure_id AS reserved_departure_id,
              r.reminder_one_at,r.reminder_two_at,
              (SELECT COUNT(*) FROM customer c WHERE c.booking_id=b.id) AS passengers,
              (SELECT COUNT(*) FROM payment p WHERE p.booking_id=b.id AND p.account_id=b.account_id AND p.status='PAID') AS payment_count,
              (SELECT COUNT(*) FROM invoice i WHERE i.booking_id=b.id AND i.account_id=b.account_id AND i.status='PAID') AS invoice_count,
              ((SELECT COUNT(*) FROM payment p WHERE p.booking_id=b.id AND p.status='PAID')
               +(SELECT COUNT(*) FROM invoice i WHERE i.booking_id=b.id AND i.status='PAID')
               +(SELECT COUNT(*) FROM vnpay_transaction v WHERE v.booking_id=b.id AND v.status='PAID')
               +(SELECT COUNT(*) FROM momo_transaction m WHERE m.booking_id=b.id AND m.status='PAID')) AS all_paid_count,
              (SELECT COUNT(*) FROM payment_reconciliation q WHERE q.booking_id=b.id AND q.status='OPEN') AS reconciliation_count,
              (SELECT MAX(day_of_pay) FROM payment p WHERE p.booking_id=b.id AND p.account_id=b.account_id AND p.status='PAID') AS payment_at,
              (SELECT MAX(day_of_pay) FROM invoice i WHERE i.booking_id=b.id AND i.account_id=b.account_id AND i.status='PAID') AS invoice_at,
              (SELECT MAX(total_booking_tour_expense) FROM payment p WHERE p.booking_id=b.id AND p.account_id=b.account_id AND p.status='PAID') AS payment_amount,
              (SELECT MAX(total_booking_tour_expense) FROM invoice i WHERE i.booking_id=b.id AND i.account_id=b.account_id AND i.status='PAID') AS invoice_amount
            FROM booking b JOIN users u ON u.id=b.account_id JOIN tour_departure d ON d.id=b.tour_departure_id
            JOIN tour t ON t.id=d.tour_id LEFT JOIN booking_reservation r ON r.booking_id=b.id WHERE b.id=? AND d.operational_status IN ('OPEN','REPLACEMENT_ONLY')
            """, id);
        if (rows.size() != 1) return Optional.empty();
        var r = rows.getFirst();
        LocalDateTime paid = later(date(r.get("payment_at")), date(r.get("invoice_at")));
        return Optional.of(new State(id, number(r.get("account_id")), number(r.get("departure_id")),
                (String)r.get("email"), (String)r.get("username"), (String)r.get("tour_title"), (String)r.get("status"),
                later(date(r.get("created_at")),date(r.get("last_change_at"))), date(r.get("departure_date")), number(r.get("passengers")),
                (String)r.get("reservation_status"), r.get("reserved_departure_id")==null
                    || (number(r.get("reserved_departure_id"))==number(r.get("departure_id"))
                        && date(r.get("reserved_departure_at")).equals(date(r.get("departure_date")))),
                date(r.get("expires_at")), date(r.get("reminder_one_at")), date(r.get("reminder_two_at")),
                number(r.get("payment_count")), number(r.get("invoice_count")), number(r.get("all_paid_count")),
                number(r.get("reconciliation_count")) > 0, paid, amount(r.get("payment_amount")), amount(r.get("invoice_amount"))));
    }
    private static int number(Object value) { return ((Number)value).intValue(); }
    private static BigDecimal amount(Object value) { return value == null ? null : new BigDecimal(value.toString()); }
    static LocalDateTime date(Object value) {
        if (value == null) return null;
        return value instanceof LocalDateTime date ? date : ((java.sql.Timestamp)value).toLocalDateTime();
    }
    private static LocalDateTime later(LocalDateTime a, LocalDateTime b) {
        return a == null ? b : b == null ? a : a.isAfter(b) ? a : b;
    }
}
