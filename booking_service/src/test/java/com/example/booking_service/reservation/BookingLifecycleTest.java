package com.example.booking_service.reservation;

import com.example.booking_service.dto.request.*;
import com.example.booking_service.dto.response.BookingResponse;
import com.example.booking_service.notification.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookingLifecycleTest {
    LifecycleTestDatabase db;
    LifecycleTestDatabase.MutableClock clock;
    BookingLifecycleService lifecycle;
    ReservationExpiryService expiry;
    BookingAccess access;
    @BeforeEach void setup() throws Exception {
        db = new LifecycleTestDatabase(); clock = new LifecycleTestDatabase.MutableClock(LocalDateTime.of(2026,11,1,10,0));
        var outbox = new MailOutboxService(new MailOutboxRepository(db.jdbc, db.manager), JsonMapper.builder().findAndAddModules().build(), clock);
        var events = new BookingMailEvents(db.jdbc, outbox, clock);
        expiry = new ReservationExpiryService(db.jdbc, db.manager, events, clock);
        access = mock(BookingAccess.class); when(access.accountId()).thenReturn(7);
        lifecycle = new BookingLifecycleService(db.jdbc, db.manager, new ReservationPolicy(), expiry, events, access, clock);
    }
    BookingRequest request() {
        return BookingRequest.builder().userId("7").tourDepartureId("1").listOfCustomers(List.of(
                CustomerRequest.builder().fullName("Danh").birthdate("2000-01-01").gender("Male").address("Hà Nội").build())).build();
    }
    int create() { return Integer.parseInt(lifecycle.create(request()).getId()); }
    @Test void holdsSeatsAndEnqueuesAcknowledgementAtomically() {
        int id = create(); assertEquals(9, db.seats()); assertEquals(1, db.count("mail_outbox"));
        var booking = lifecycle.get(id); assertEquals("HELD", booking.getReservationStatus());
        assertEquals("PENDING_PAYMENT", booking.getStatus());
        assertEquals("3350000", booking.getTotalAmount());
        assertEquals("VND", booking.getCurrency());
        assertEquals("3350000.00", booking.getUnitPrices().get("ADULT"));
        assertEquals("2026-11-07T08:00", booking.getPaymentDeadline());
        // ISO-8601 so every browser can parse it; Timestamp.toString() used to emit a space.
        assertTrue(booking.getCreatedAt().startsWith("2026-11-01T10:00"));
        assertTrue(db.jdbc.queryForObject("SELECT payload FROM mail_outbox", String.class).contains("holdUntil"));
    }
    @Test void immediateCheckoutHasNoPrematureSuccessEmail() {
        clock.set(LocalDateTime.of(2026,11,13,10,0)); int id = create();
        assertTrue(lifecycle.get(id).isPaymentRequiredNow()); assertEquals("CHECKOUT", lifecycle.get(id).getReservationStatus());
        assertEquals(0, db.count("mail_outbox")); assertEquals(9, db.seats());
    }
    @Test void cancellationIsIdempotentAndPreservesOriginalCreatedAt() {
        int id = create(); var original = lifecycle.get(id).getCreatedAt(); clock.set(LocalDateTime.of(2026,11,2,10,0));
        lifecycle.cancel(id); lifecycle.cancel(id); assertEquals(10, db.seats());
        assertEquals(original, lifecycle.get(id).getCreatedAt()); assertEquals(2, db.count("mail_outbox"));
        assertEquals("CANCELLED", lifecycle.get(id).getStatus());
    }
    @Test void cancellationClosesTheActiveChangeRequestAndFreesTheBooking() {
        int id = create();
        insertChange(id);
        lifecycle.cancel(id);
        assertChangeWithdrawn(id);
    }
    @Test void expiryClosesTheActiveChangeRequest() {
        int id = create();
        insertChange(id);
        clock.set(LocalDateTime.of(2026,11,8,8,0));
        expiry.sweep();
        assertEquals("EXPIRED", lifecycle.get(id).getStatus());
        assertChangeWithdrawn(id);
    }
    private void insertChange(int bookingId) {
        LocalDateTime now = LocalDateTime.now(clock);
        db.jdbc.update("""
                INSERT INTO booking_change (client_key,booking_id,account_id,kind,source_departure_id,target_departure_id,
                source_departure_at,source_return_at,target_departure_at,target_return_at,status,reason,requested_by,created_at,updated_at)
                VALUES (?,?,7,'CUSTOMER',1,1,?,?,?,?,'PENDING','Đổi ngày',7,?,?)
                """, "change-" + bookingId, bookingId, now, now, now, now, now, now);
    }
    private void assertChangeWithdrawn(int bookingId) {
        var change = db.jdbc.queryForMap("SELECT id,status,active_key FROM booking_change WHERE booking_id=?", bookingId);
        assertEquals("WITHDRAWN", change.get("status"));
        assertNull(change.get("active_key"));
        assertEquals("WITHDRAWN", db.jdbc.queryForObject(
                "SELECT action FROM booking_change_event WHERE booking_change_id=?", String.class, change.get("id")));
    }
    @Test void expiryReturnsSeatsExactlyOnceAndAllowsRebooking() {
        int id = create(); clock.set(LocalDateTime.of(2026,11,7,8,0)); expiry.sweep(); expiry.sweep();
        assertEquals(10, db.seats()); assertEquals("EXPIRED", lifecycle.get(id).getReservationStatus());
        assertEquals("EXPIRED", lifecycle.get(id).getStatus());
        assertEquals(2, db.count("mail_outbox")); assertNotEquals(id, create()); assertEquals(9, db.seats());
    }
    @Test void createCleansExpiredSeatsEvenWithoutScheduledWorker() {
        create(); db.jdbc.update("UPDATE tour_departure SET available_seats=0");
        clock.set(LocalDateTime.of(2026,11,8,8,0)); create(); assertEquals(0, db.seats());
    }
    @Test void versionsTrackStockChangesAndRetriesDoNotReturnSeatsTwice() {
        int id=create();assertEquals(1L,db.jdbc.queryForObject("SELECT version FROM tour_departure WHERE id=1",Long.class));
        lifecycle.cancel(id);lifecycle.cancel(id);assertEquals(10,db.seats());assertEquals(2L,db.jdbc.queryForObject("SELECT version FROM tour_departure WHERE id=1",Long.class));
        create();clock.set(LocalDateTime.of(2026,11,8,8,0));expiry.sweep();expiry.sweep();assertEquals(10,db.seats());
        assertEquals(4L,db.jdbc.queryForObject("SELECT version FROM tour_departure WHERE id=1",Long.class));
    }
    @Test void openReconciliationPreventsAutomaticExpiryAndCancellation() {
        int id=create();db.jdbc.update("INSERT INTO payment_reconciliation(txn_ref,booking_id,reason,provider_paid_at,amount_vnd,created_at) VALUES('review',?,'TEST',?,3350000,?)",id,LocalDateTime.now(clock),LocalDateTime.now(clock));
        clock.set(LocalDateTime.of(2026,11,8,8,0));expiry.sweep();assertEquals(9,db.seats());assertThrows(ResponseStatusException.class,()->lifecycle.cancel(id));
    }
    @Test void paidEvidencePreventsAutomaticExpiryAndCancellation() {
        int id = create(); db.jdbc.update("INSERT INTO invoice (booking_id,status) VALUES (?,'PAID')", id);
        clock.set(LocalDateTime.of(2026,11,8,8,0)); expiry.sweep();
        assertEquals(9, db.seats()); assertThrows(ResponseStatusException.class, () -> lifecycle.cancel(id));
    }
    @Test void pendingMomoCheckoutKeepsSeatsDuringTheIpnGraceWindow() {
        int id = create();
        LocalDateTime now = LocalDateTime.of(2026, 11, 8, 8, 0);
        clock.set(now);
        db.jdbc.update("INSERT INTO momo_transaction(order_id,booking_id,status,created_at,expires_at) VALUES('MomoGrace',?,'PENDING',?,?)",
                id, now.minusMinutes(10), now.minusMinutes(3));
        expiry.sweep();
        assertEquals(9, db.seats());
        assertEquals("HELD", db.jdbc.queryForObject("SELECT status FROM booking_reservation WHERE booking_id=?", String.class, id));
    }
    @Test void inconsistentPassengerCountCannotBeAutomaticallyReleased() {
        int id=create();db.jdbc.update("UPDATE booking_reservation SET seats=2 WHERE booking_id=?",id);
        clock.set(LocalDateTime.of(2026,11,8,8,0));expiry.sweep();assertEquals(9,db.seats());
        assertThrows(ResponseStatusException.class,()->lifecycle.cancel(id));assertEquals(9,db.seats());
    }
    @Test void expiryReachesLaterPagesWhenFirstFiftyDeparturesRequireReconciliation() {
        db.jdbc.update("INSERT INTO tour_departure (id,tour_id,departure_date,available_seats) VALUES (51,1,?,10)",LocalDateTime.of(2026,11,20,6,0));
        var booking=request();booking.setTourDepartureId("51");
        int lastBooking=Integer.parseInt(lifecycle.create(booking).getId());
        LocalDateTime created=LocalDateTime.now(clock);
        // Seed the 50 reconciled holds directly; creating them through the service would hit the per-account hold cap.
        for (int departure=1;departure<=50;departure++) {
            if (departure>1) db.jdbc.update("INSERT INTO tour_departure (id,tour_id,departure_date,available_seats) VALUES (?,1,?,10)",departure,LocalDateTime.of(2026,11,20,6,0));
            db.jdbc.update("INSERT INTO booking (account_id,tour_departure_id,status,created_at) VALUES (7,?,'CONFIRMED',?)",departure,created);
            int bookingId=db.jdbc.queryForObject("SELECT MAX(id) FROM booking",Integer.class);
            db.jdbc.update("INSERT INTO customer (booking_id,booking_type) VALUES (?,'ADULT')",bookingId);
            db.jdbc.update("""
                INSERT INTO booking_reservation (booking_id,departure_id,departure_at,seats,status,expires_at,created_at,updated_at)
                VALUES (?,?,?,1,'HELD',?,?,?)
                """,bookingId,departure,LocalDateTime.of(2026,11,20,6,0),LocalDateTime.of(2026,11,7,8,0),created,created);
            db.jdbc.update("UPDATE tour_departure SET available_seats=available_seats-1 WHERE id=?",departure);
            db.jdbc.update("INSERT INTO invoice (booking_id,status) VALUES (?,'PAID')",bookingId);
        }
        clock.set(LocalDateTime.of(2026,11,8,8,0));expiry.sweep();expiry.sweep();
        assertEquals("EXPIRED",db.jdbc.queryForObject("SELECT status FROM booking_reservation WHERE booking_id=?",String.class,lastBooking));
        assertEquals(10,db.jdbc.queryForObject("SELECT available_seats FROM tour_departure WHERE id=51",Integer.class));
        assertEquals(50,db.jdbc.queryForObject("SELECT COUNT(*) FROM booking_reservation WHERE status='HELD'",Integer.class));
        assertEquals(9,db.seats());
    }
    @Test void outboxFailureRollsBackBookingPassengersHoldAndSeats() {
        db.jdbc.execute("ALTER TABLE mail_outbox ADD CONSTRAINT reject_mail CHECK (event_type='TEST_MAIL')");
        assertThrows(RuntimeException.class, this::create); assertEquals(10, db.seats());
        assertEquals(0, db.count("booking")); assertEquals(0, db.count("customer")); assertEquals(0, db.count("booking_price_snapshot")); assertEquals(0, db.count("booking_reservation"));
    }
    @Test void invalidCustomerAndImpersonationCannotHoldSeats() {
        var bad = request(); bad.getListOfCustomers().getFirst().setBirthdate("2099-01-01");
        assertThrows(IllegalArgumentException.class, () -> lifecycle.create(bad));
        var impersonated = request(); impersonated.setUserId("8");
        assertThrows(ResponseStatusException.class, () -> lifecycle.create(impersonated)); assertEquals(10, db.seats());
    }
    @Test void concurrentBookingsCannotOversellLastSeat() throws Exception {
        db.jdbc.update("UPDATE tour_departure SET available_seats=1");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<Boolean> book = () -> { gate.await(); try { create(); return true; } catch (ResponseStatusException soldOut) { return false; } };
            var a = pool.submit(book); var b = pool.submit(book); gate.countDown();
            assertNotEquals(a.get(10,TimeUnit.SECONDS), b.get(10,TimeUnit.SECONDS));
            assertEquals(0, db.seats()); assertEquals(1, db.count("booking_reservation"));
        }
    }
    @Test void expiryAndManualCancellationCannotDoubleRelease() throws Exception {
        int id = create(); clock.set(LocalDateTime.of(2026,11,7,8,0));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> expiry.sweep()); var b = pool.submit(() -> lifecycle.cancel(id));
            a.get(10,TimeUnit.SECONDS); b.get(10,TimeUnit.SECONDS);
        }
        assertEquals(10, db.seats()); assertEquals(2, db.count("mail_outbox"));
    }
    @Test void migrationCanBeImportedAgainWithoutChangingSeats() throws Exception {
        create(); db.run("db/003_booking_reservation.sql", System.getenv("LIFECYCLE_TEST_JDBC_URL") != null);
        assertEquals(9, db.seats()); assertEquals(1, db.count("booking_reservation"));
    }
    @Test void lockedAccountCannotCreateBookingOrConsumeSeats(){
        db.jdbc.update("UPDATE users SET status='LOCKED' WHERE id=7");
        assertThrows(ResponseStatusException.class,()->create());assertEquals(10,db.seats());assertEquals(0,db.count("booking"));assertEquals(0,db.count("mail_outbox"));
    }
    /**
     * The profile history page is served by the same list endpoint the admin screens use, so a staff
     * account needs mine=true to get its own rows instead of the newest global page.
     */
    @Test void ownListStaysScopedWhenTheAccountMayReadEveryone() {
        int own = create();
        db.jdbc.update("INSERT INTO booking (account_id,tour_departure_id,status,created_at) VALUES (8,1,'CONFIRMED',?)", LocalDateTime.now(clock));
        when(access.can("BOOKINGS_GET_ALL")).thenReturn(true);
        assertEquals(List.of(String.valueOf(own)),
                lifecycle.list(0, 10, true).stream().map(BookingResponse::getId).toList());
        assertEquals(2, lifecycle.list(0, 10, false).size());
    }
    /**
     * The sweep walks departures in id order, so a departure that always fails would starve every
     * later departure; only the failing departure may be left for the next tick.
     */
    @Test void poisonDepartureDoesNotStarveLaterDepartures() {
        int first = create();
        db.jdbc.update("INSERT INTO tour_departure (id,tour_id,departure_date,available_seats) VALUES (51,1,?,10)", LocalDateTime.of(2026,11,20,6,0));
        var other = request(); other.setTourDepartureId("51");
        int later = Integer.parseInt(lifecycle.create(other).getId());
        // Only departure 1 is poisoned: the CHECK accepts the row as it stands today and rejects the
        // version bump the expiry write makes, so its transaction rolls back on every sweep.
        db.jdbc.execute("ALTER TABLE tour_departure ADD CONSTRAINT poison_first CHECK (id<>1 OR version<2)");
        clock.set(LocalDateTime.of(2026,11,8,8,0));
        expiry.sweep();
        assertEquals("HELD", db.jdbc.queryForObject("SELECT status FROM booking_reservation WHERE booking_id=?", String.class, first));
        assertEquals("EXPIRED", db.jdbc.queryForObject("SELECT status FROM booking_reservation WHERE booking_id=?", String.class, later));
        assertEquals(10, db.jdbc.queryForObject("SELECT available_seats FROM tour_departure WHERE id=51", Integer.class));
    }
}
