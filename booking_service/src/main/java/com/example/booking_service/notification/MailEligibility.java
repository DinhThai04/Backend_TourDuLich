package com.example.booking_service.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;

/** Rechecks current facts immediately before dispatch; producers still own business rules. */
@Component
public class MailEligibility {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public MailEligibility(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    public String skipReason(MailOutboxItem item, MailPayload payload) {
        if (item.eventType() == MailEventType.TEST_MAIL) return null;
        if (item.eventType() == MailEventType.BOOKING_CHANGE_UPDATED) {
            var changes=jdbc.queryForList("""
                SELECT c.*,u.email,b.tour_departure_id AS current_departure_id,b.status AS current_booking_status,
                    d.operational_status AS current_operation,
                    (SELECT MAX(later.applied_at) FROM booking_change later WHERE later.booking_id=c.booking_id) AS latest_applied_at
                FROM booking_change c JOIN users u ON u.id=c.account_id JOIN booking b ON b.id=c.booking_id
                JOIN tour_departure d ON d.id=b.tour_departure_id
                WHERE c.id=? AND c.booking_id=? AND c.account_id=?
                """,payload.changeId(),item.bookingId(),item.accountId());
            if(changes.size()!=1)return "Change request no longer exists or owner changed";
            var c=changes.getFirst();
            if("APPROVED".equals(payload.changeStatus()) && (!"CONFIRMED".equals(c.get("current_booking_status"))
                || ((Number)c.get("current_departure_id")).intValue()!=payload.departureId()
                || java.util.List.of("SUSPENDED","CANCELLED").contains(String.valueOf(c.get("current_operation")))
                || !Objects.equals(c.get("applied_at"),c.get("latest_applied_at"))))
                return "Confirmed schedule was superseded by another change";
            return item.recipient().equalsIgnoreCase(String.valueOf(c.get("email")))
                && Objects.equals(c.get("status"),payload.changeStatus())
                && Objects.equals(((Number)c.get("target_departure_id")).intValue(),payload.departureId())
                && com.example.booking_service.change.ChangeLedger.date(c.get("target_departure_at")).equals(payload.departureAt())
                ? null : "Schedule notice was superseded";
        }
        if (item.eventType() == MailEventType.PAYMENT_REMINDER || item.eventType() == MailEventType.DEPARTURE_REMINDER) {
            var state=new ReminderStateReader(jdbc).read(item.bookingId());
            if(state.isEmpty()) return "Reminder booking no longer exists";
            var s=state.get();
            if(!Objects.equals(item.accountId(),s.accountId()) || !item.recipient().equalsIgnoreCase(s.recipient())
                    || !Objects.equals(payload.departureId(),s.departureId()) || !Objects.equals(payload.departureAt(),s.departureAt()))
                return "Reminder owner, email or departure changed";
            var selection=new ReminderRules().select(s,LocalDateTime.now(clock));
            if(selection.isEmpty()) return "Reminder is no longer due or valid";
            var current=selection.get();
            return current.type()==item.eventType() && Objects.equals(current.due(),payload.reminderAt())
                    && Objects.equals(current.daysBefore(),payload.daysBefore()) && Objects.equals(current.deadline(),payload.holdUntil())
                    ? null : "Reminder was superseded by a newer milestone";
        }
        var rows = jdbc.queryForList("""
            SELECT b.status,b.account_id,b.tour_departure_id,d.departure_date,d.operational_status,u.email
            FROM booking b JOIN tour_departure d ON d.id=b.tour_departure_id
            JOIN users u ON u.id=b.account_id WHERE b.id=?
            """, item.bookingId());
        if (rows.size() != 1) return "Booking, departure or account no longer exists";
        var row = rows.getFirst();
        if (!Objects.equals(item.accountId(), ((Number)row.get("account_id")).intValue())
                || !item.recipient().equalsIgnoreCase(String.valueOf(row.get("email"))))
            return "Booking owner or email changed";
        int payments = jdbc.queryForObject("SELECT COUNT(*) FROM payment WHERE booking_id=? AND account_id=? AND status='PAID'",
                Integer.class, item.bookingId(), item.accountId());
        int invoices = jdbc.queryForObject("SELECT COUNT(*) FROM invoice WHERE booking_id=? AND account_id=? AND status='PAID'",
                Integer.class, item.bookingId(), item.accountId());
        boolean paid = payments > 0 && invoices > 0;
        if ((payments > 0) != (invoices > 0)) return "Payment and invoice require reconciliation";
        boolean active = java.util.List.of("PENDING_PAYMENT", "CONFIRMED").contains(String.valueOf(row.get("status")));
        if (item.eventType() == MailEventType.BOOKING_CANCELLED || item.eventType() == MailEventType.BOOKING_EXPIRED)
            return active ? "Booking is no longer cancelled" : null;
        if (item.eventType() == MailEventType.PAYMENT_SUCCEEDED) {
            if(java.util.List.of("SUSPENDED","CANCELLED").contains(String.valueOf(row.get("operational_status")))) return "Receipt departure suspended; contact shop";
            if(!Objects.equals(payload.departureId(),((Number)row.get("tour_departure_id")).intValue())
                || !Objects.equals(payload.departureAt(),com.example.booking_service.change.ChangeLedger.date(row.get("departure_date"))))
                return "Receipt departure changed; see schedule confirmation";
            // Receipts are emitted for both providers; the reference is unique per provider table.
            int settled = jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM vnpay_transaction WHERE booking_id=? AND account_id=?
                            AND txn_ref=? AND status='PAID' AND amount_vnd=?)
                     + (SELECT COUNT(*) FROM momo_transaction WHERE booking_id=? AND account_id=?
                            AND order_id=? AND status='PAID' AND amount_vnd=?)
                """, Integer.class, item.bookingId(), item.accountId(), payload.transactionRef(), payload.amountVnd(),
                    item.bookingId(), item.accountId(), payload.transactionRef(), payload.amountVnd());
            return paid && settled == 1 ? null : "Verified payment no longer matches event";
        }
        if (!active) return "Booking is cancelled";
        if(java.util.List.of("SUSPENDED","CANCELLED").contains(String.valueOf(row.get("operational_status")))) return "Departure suspended";
        if (payload.departureId() == null
                || payload.departureId() != ((Number)row.get("tour_departure_id")).intValue())
            return "Departure changed";
        Object date = row.get("departure_date");
        LocalDateTime departure = date instanceof LocalDateTime value ? value : ((java.sql.Timestamp)date).toLocalDateTime();
        if (!departure.equals(payload.departureAt())) return "Departure date changed";
        if (!departure.isAfter(LocalDateTime.now(clock))) return "Tour has departed";
        if (item.eventType() == MailEventType.BOOKING_CREATED && payload.holdUntil() != null) {
            var holds = jdbc.queryForList("SELECT status,expires_at FROM booking_reservation WHERE booking_id=?", item.bookingId());
            if (holds.size() != 1 || !"HELD".equals(holds.getFirst().get("status"))) return "Reservation no longer held";
            Object expiry = holds.getFirst().get("expires_at");
            LocalDateTime until = expiry instanceof LocalDateTime value ? value : ((java.sql.Timestamp)expiry).toLocalDateTime();
            if (!until.equals(payload.holdUntil()) || !until.isAfter(LocalDateTime.now(clock))) return "Reservation deadline changed or expired";
        }
        return switch (item.eventType()) {
            case BOOKING_CREATED -> paid ? "Unpaid acknowledgement is obsolete" : null;
            default -> "Unsupported mail event";
        };
    }
}
