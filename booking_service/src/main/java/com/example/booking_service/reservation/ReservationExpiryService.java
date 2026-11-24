package com.example.booking_service.reservation;

import com.example.booking_service.notification.MailEventType;
import com.example.booking_service.repository.httpclient.InvoiceInternalClient;
import com.example.booking_service.repository.httpclient.PaymentInternalClient;
import com.example.booking_service.repository.httpclient.TourInternalClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;

@Service
public class ReservationExpiryService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ReservationExpiryService.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final BookingMailEvents events;
    private final Clock clock;
    private final TourInternalClient tourClient;
    private final PaymentInternalClient paymentClient;
    private final InvoiceInternalClient invoiceClient;

    @Autowired
    public ReservationExpiryService(JdbcTemplate jdbc, PlatformTransactionManager manager, BookingMailEvents events,
                                    Clock clock, TourInternalClient tourClient, PaymentInternalClient paymentClient,
                                    InvoiceInternalClient invoiceClient) {
        this.jdbc = jdbc;
        this.events = events;
        this.clock = clock;
        this.tourClient = tourClient;
        this.paymentClient = paymentClient;
        this.invoiceClient = invoiceClient;
        this.transactions = new TransactionTemplate(manager);
    }

    public void expireDeparture(int departureId) {
        Map<String, Object> departure;
        try {
            departure = tourClient.getDeparture(departureId);
        } catch (Exception e) {
            log.warn("Tour service unavailable while expiring departure {}: {}", departureId, e.getMessage());
            return;
        }
        if (departure == null || departure.isEmpty()) return;
        boolean suspended = "SUSPENDED".equals(departure.get("operational_status"));

        transactions.executeWithoutResult(tx -> {
            LocalDateTime now = LocalDateTime.now(clock).withNano(0);
            var candidates = jdbc.queryForList("""
                SELECT booking_id FROM booking_reservation WHERE departure_id=?
                  AND status IN ('HELD','CHECKOUT') AND expires_at<=? ORDER BY booking_id
                """, departureId, now);
            for (var candidate : candidates) {
                int id = ((Number) candidate.get("booking_id")).intValue();
                var booking = jdbc.queryForMap("SELECT id,tour_departure_id FROM booking WHERE id=? FOR UPDATE", id);
                var hold = jdbc.queryForMap("SELECT * FROM booking_reservation WHERE booking_id=? FOR UPDATE", id);
                int seats = ((Number) hold.get("seats")).intValue();
                if (((Number) booking.get("tour_departure_id")).intValue() != departureId
                        || ((Number) hold.get("departure_id")).intValue() != departureId
                        || !java.util.List.of("HELD", "CHECKOUT").contains(hold.get("status")) || seats <= 0
                        || seats != jdbc.queryForObject("SELECT COUNT(*) FROM customer WHERE booking_id=?", Integer.class, id)) {
                    continue;
                }
                if (suspended && hasActiveShopOffer(id, departureId)) continue;
                // Any evidence of payment prevents automatic release, even when records disagree.
                if (paidEvidence(id)) continue;
                // A checkout the provider may still confirm (IPN in transit) must keep its seats.
                if (paymentInFlight(id, now)) continue;

                // Release seats via Tour internal API
                tourClient.releaseHold(departureId, seats);

                jdbc.update("UPDATE booking SET status='EXPIRED' WHERE id=? AND status IN ('PENDING_PAYMENT','CONFIRMED')", id);
                jdbc.update("UPDATE booking_reservation SET status='EXPIRED',cancel_reason='PAYMENT_DEADLINE',updated_at=? WHERE booking_id=?", now, id);
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

    /** Only orders actually awaiting a shop decision or replacement keep their hold during an incident. */
    boolean hasActiveShopOffer(int bookingId, int departureId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM booking_change
                WHERE booking_id=? AND source_departure_id=? AND kind='SHOP' AND active_key=1
                """, Integer.class, bookingId, departureId) > 0;
    }

    /** True while a VNPay or MoMo transaction may still be paid or confirmed, including the IPN grace window. */
    public boolean paymentInFlight(int bookingId, LocalDateTime now) {
        try {
            Map<String, Object> ev = paymentClient.getBookingPaymentEvidence(bookingId);
            return ev == null || Boolean.TRUE.equals(ev.get("inFlight"));
        } catch (Exception e) {
            // Fail-closed: if payment service is unreachable, assume payment may be in flight.
            return true;
        }
    }

    public boolean paidEvidence(int id) {
        try {
            Map<String, Object> ev = paymentClient.getBookingPaymentEvidence(id);
            if (ev == null || Boolean.TRUE.equals(ev.get("paidEvidence"))) {
                return true;
            }
        } catch (Exception e) {
            return true;
        }
        try {
            Map<String, Object> inv = invoiceClient.getInvoiceByBookingId(id);
            if (inv != null && "PAID".equals(inv.get("status"))) {
                return true;
            }
        } catch (feign.FeignException.NotFound ignored) {
            // No invoice yet
        } catch (Exception e) {
            return true;
        }
        return false;
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
            for (int departure : due) {
                try {
                    expireDeparture(departure);
                } catch (RuntimeException error) {
                    log.warn("Skipped expiry for departure {} ({}); will retry on the next sweep", departure, error.getMessage(), error);
                }
            }
            afterDeparture = due.getLast();
            if (due.size() < 50) return;
        }
    }
}
