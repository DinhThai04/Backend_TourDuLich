package com.example.booking_service.reservation;

import com.example.booking_service.notification.MailEventType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.time.LocalDateTime;

@Service
public class ReservationExpiryService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ReservationExpiryService.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final BookingMailEvents events;
    private final Clock clock;
    public ReservationExpiryService(JdbcTemplate jdbc, PlatformTransactionManager manager, BookingMailEvents events, Clock clock) {
        this.jdbc = jdbc; this.events = events; this.clock = clock;
        transactions = new TransactionTemplate(manager);
    }
    public void expireDeparture(int departureId) {
        transactions.executeWithoutResult(tx -> {
            var departures = jdbc.queryForList("SELECT * FROM tour_departure WHERE id=? FOR UPDATE", departureId);
            // A suspended departure keeps its unpaid holds until the incident is resolved, so the
            // customer is not expired out from under a pending replacement offer. Once the
            // departure is cancelled nobody can pay for it, so due holds must be released.
            if (departures.isEmpty() || "SUSPENDED".equals(String.valueOf(departures.getFirst().get("operational_status")))) return;
            LocalDateTime now = LocalDateTime.now(clock).withNano(0);
            var candidates = jdbc.queryForList("""
                SELECT booking_id FROM booking_reservation WHERE departure_id=?
                  AND status IN ('HELD','CHECKOUT') AND expires_at<=? ORDER BY booking_id
                """, departureId, now);
            for (var candidate : candidates) {
                int id = ((Number)candidate.get("booking_id")).intValue();
                var booking=jdbc.queryForMap("SELECT id,tour_departure_id FROM booking WHERE id=? FOR UPDATE", id);
                var hold = jdbc.queryForMap("SELECT * FROM booking_reservation WHERE booking_id=? FOR UPDATE", id);
                int seats=((Number)hold.get("seats")).intValue();
                if(((Number)booking.get("tour_departure_id")).intValue()!=departureId || ((Number)hold.get("departure_id")).intValue()!=departureId
                        || !java.util.List.of("HELD","CHECKOUT").contains(hold.get("status")) || seats<=0
                        || seats!=jdbc.queryForObject("SELECT COUNT(*) FROM customer WHERE booking_id=?",Integer.class,id)) continue;
                // Any evidence of payment prevents automatic release, even when records disagree.
                if (paidEvidence(id)) continue;
                // A checkout the provider may still confirm (IPN in transit) must keep its seats.
                if (paymentInFlight(id, now)) continue;
                // Clamp to capacity: a hold must always be released, otherwise it retries forever.
                // available_seats is SMALLINT, so legacy rows without capacity cap at 32767.
                jdbc.update("UPDATE tour_departure SET available_seats=LEAST(COALESCE(capacity,32767),available_seats+?),version=version+1 WHERE id=?", hold.get("seats"), departureId);
                jdbc.update("UPDATE booking SET status='EXPIRED' WHERE id=? AND status IN ('PENDING_PAYMENT','CONFIRMED')", id);
                jdbc.update("UPDATE booking_reservation SET status='EXPIRED',cancel_reason='PAYMENT_DEADLINE',updated_at=? WHERE booking_id=?", now, id);
                // An expired booking cannot honour a reschedule offer, and a lingering active row
                // would keep blocking new requests for the same booking.
                for (var change : jdbc.queryForList("SELECT id,requested_by FROM booking_change WHERE booking_id=? AND active_key=1", id)) {
                    long changeId = ((Number) change.get("id")).longValue();
                    jdbc.update("UPDATE booking_change SET status='WITHDRAWN',active_key=NULL,version=version+1,updated_at=? WHERE id=?", now, changeId);
                    jdbc.update("""
                            INSERT INTO booking_change_event(booking_change_id,actor_id,action,note,created_at)
                            VALUES(?,?,?,?,?)
                            """, changeId, change.get("requested_by"), "WITHDRAWN", "Booking hết hạn giữ chỗ; hệ thống đóng yêu cầu dời lịch", now);
                }
                events.enqueue(id, MailEventType.BOOKING_EXPIRED, null);
            }
        });
    }
    /** True while a VNPay or MoMo transaction may still be paid or confirmed, including the IPN grace window. */
    public boolean paymentInFlight(int bookingId, LocalDateTime now) {
        LocalDateTime grace = now.minusMinutes(5);
        return jdbc.queryForObject("""
            SELECT (SELECT COUNT(*) FROM vnpay_transaction
                     WHERE booking_id=? AND status='PENDING' AND expires_at>?)
                 + (SELECT COUNT(*) FROM momo_transaction
                     WHERE booking_id=? AND status='PENDING' AND expires_at>?)
            """, Integer.class, bookingId, grace, bookingId, grace) > 0;
    }
    public boolean paidEvidence(int id) {
        return jdbc.queryForObject("""
            SELECT (SELECT COUNT(*) FROM payment WHERE booking_id=? AND status='PAID')
                 + (SELECT COUNT(*) FROM invoice WHERE booking_id=? AND status='PAID')
                 + (SELECT COUNT(*) FROM vnpay_transaction WHERE booking_id=? AND status='PAID')
                 + (SELECT COUNT(*) FROM momo_transaction WHERE booking_id=? AND status='PAID')
                 + (SELECT COUNT(*) FROM payment_reconciliation WHERE booking_id=? AND status='OPEN')
            """, Integer.class, id, id, id, id, id) > 0;
    }
    public void sweep() {
        LocalDateTime cutoff = LocalDateTime.now(clock);
        int afterDeparture = 0;
        while (true) {
            var due = jdbc.queryForList("""
                SELECT DISTINCT departure_id FROM booking_reservation
                WHERE status IN ('HELD','CHECKOUT') AND expires_at<=? AND departure_id>?
                ORDER BY departure_id LIMIT 50
                """, Integer.class, cutoff, afterDeparture);
            if (due.isEmpty()) return;
            // Paid/inconsistent holds remain unchanged, but cannot monopolise the first page. Each
            // departure is its own transaction, so a failing one is retried on the next sweep instead
            // of aborting the queue and starving every departure with a larger id forever.
            for (int departure : due) {
                try { expireDeparture(departure); }
                catch (RuntimeException error) {
                    log.warn("Skipped expiry for departure {} ({}); will retry on the next sweep", departure, error.getMessage(), error);
                }
            }
            afterDeparture = due.getLast();
            if (due.size() < 50) return;
        }
    }
}
