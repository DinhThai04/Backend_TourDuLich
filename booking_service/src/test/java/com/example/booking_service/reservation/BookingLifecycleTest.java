package com.example.booking_service.reservation;

import com.example.booking_service.dto.request.*;
import com.example.booking_service.config.BookingMailOutboxPublisher;
import com.example.booking_service.config.NotificationMailClient;
import com.example.booking_service.notification.MailEventType;
import com.example.booking_service.repository.httpclient.AuthInternalClient;
import com.example.booking_service.repository.httpclient.InvoiceInternalClient;
import com.example.booking_service.repository.httpclient.PricingInternalClient;
import com.example.booking_service.repository.httpclient.TourInternalClient;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookingLifecycleTest {
    LifecycleTestDatabase db;
    LifecycleTestDatabase.MutableClock clock;
    BookingLifecycleService lifecycle;
    ReservationExpiryService expiry;
    BookingAccess access;
    BookingMailOutboxPublisher publisher;
    AuthInternalClient authClient;
    @BeforeEach void setup() throws Exception {
        db = new LifecycleTestDatabase(); 
        clock = new LifecycleTestDatabase.MutableClock(LocalDateTime.of(2026,11,1,10,0));
        var authClient = mock(AuthInternalClient.class);
        this.authClient = authClient;
        var tourClient = mock(TourInternalClient.class);
        var invoiceClient = mock(InvoiceInternalClient.class);
        var pricingClient = mock(PricingInternalClient.class);
        when(authClient.getUserById(anyLong())).thenReturn(Map.of("email", "tester@example.com", "username", "tester"));
        when(authClient.getUserStatus(anyLong())).thenReturn(Map.of("status", "ACTIVE"));
        when(pricingClient.getPriceById(anyLong())).thenReturn(Map.of("id", 1, "adultPrice", 3350000, "childPrice", 2000000, "toddlerPrice", 0, "infantPrice", 0, "singleSupplementPrice", 500));
        when(invoiceClient.getInvoiceByBookingId(anyInt())).thenReturn(Map.of("status", "PAID"));
        answerDeparture(tourClient);
        doThrow(new RuntimeException("Tour service unavailable")).when(tourClient).releaseHold(anyInt(), anyInt());
        var publisher = mock(BookingMailOutboxPublisher.class);
        this.publisher = publisher;
        var payClient = com.example.booking_service.TestInternalClients.payment(db.jdbc, clock);
        var invClient = com.example.booking_service.TestInternalClients.invoice(db.jdbc);
        var tClient = com.example.booking_service.TestInternalClients.tour(db.jdbc);
        var events = new BookingMailEvents(db.jdbc, publisher, clock, authClient, tClient);
        expiry = new ReservationExpiryService(db.jdbc, db.manager, events, clock, tClient, payClient, invClient);
        access = mock(BookingAccess.class); 
        when(access.accountId()).thenReturn(7);
        lifecycle = new BookingLifecycleService(db.jdbc, db.manager, new ReservationPolicy(), expiry,
            events, access, clock,
            authClient, invClient, pricingClient, tClient);
    }
    BookingRequest request() {
        return BookingRequest.builder().userId("7").tourDepartureId("1").listOfCustomers(List.of(
                CustomerRequest.builder().fullName("Danh").birthdate("2000-01-01").gender("Male").build())).build();
    }
    int create() { return Integer.parseInt(lifecycle.create(request()).getId()); }

    String captureSendNow() {
        var arg = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(publisher, atLeastOnce()).sendNow(arg.capture());
        return arg.getAllValues().isEmpty() ? null : arg.getAllValues().get(arg.getAllValues().size() - 1);
    }

    void answerDeparture(TourInternalClient tourClient) {
        when(tourClient.getDeparture(anyInt())).thenAnswer(inv -> {
            int id = inv.getArgument(0);
            var row = db.jdbc.queryForMap("SELECT * FROM tour_departure WHERE id = ?", id);
            var map = new java.util.LinkedHashMap<String, Object>();
            for (var e : row.entrySet()) map.put(e.getKey().toLowerCase(), e.getValue());
            return map;
        });
    }

    @Test void staleDisplayedPriceCannotCreateAHoldAndTheSameKeyCanRetry() {
        var input = request();
        input.setClientKey(UUID.randomUUID().toString());
        input.setExpectedAmount("1");
        var rejected = assertThrows(ResponseStatusException.class, () -> lifecycle.create(input));
        assertEquals(409, rejected.getStatusCode().value());
        assertEquals(0, db.count("booking"));
        assertEquals(0, db.count("booking_outbox"));
        verify(publisher, never()).sendNow(anyString());
        assertEquals(10, db.seats());
        assertNull(lifecycle.findOwnAttempt(input.getClientKey()));

        input.setExpectedAmount("3350000");
        var confirmed = lifecycle.create(input);
        assertEquals(confirmed.getId(), lifecycle.findOwnAttempt(input.getClientKey()).getId());
        input.setExpectedAmount("1");
        assertEquals(confirmed.getId(), lifecycle.create(input).getId());
        assertEquals(1, db.count("booking"));
        when(access.accountId()).thenReturn(8);
        assertNull(lifecycle.findOwnAttempt(input.getClientKey()));
    }

    @Test void historyFindsAnOlderOrderWithoutShowingAnotherAccountsData() {
        db.jdbc.execute("ALTER TABLE tour_departure ADD tour_code VARCHAR(40)");
        db.jdbc.execute("ALTER TABLE tour_departure ADD return_date DATETIME(6)");
        db.jdbc.update("UPDATE tour_departure SET tour_code='SGU-TEST',return_date=departure_date WHERE id=1");
        for (int i = 0; i < 11; i++) db.jdbc.update("""
                INSERT INTO booking (account_id,tour_departure_id,status,created_at) VALUES (7,1,'CONFIRMED',?)
                """, LocalDateTime.now(clock));
        db.jdbc.update("INSERT INTO invoice (account_id,booking_id,status) VALUES (7,1,'PAID')");
        db.jdbc.update("INSERT INTO booking_reservation (booking_id,departure_id,departure_at,seats,status,expires_at,reminder_one_at,reminder_two_at,cancel_reason,created_at,updated_at) VALUES (1,1,?,1,'PAID',?,?,?,?,?,?)",
                LocalDateTime.of(2026,11,20,6,0), LocalDateTime.now(clock), null, null, null, LocalDateTime.now(clock), LocalDateTime.now(clock));

        var recent = lifecycle.ownHistory(0, null, null, null, null);
        assertEquals(8, recent.items().size());
        assertTrue(recent.hasNext());
        assertEquals(3, lifecycle.ownHistory(1, null, null, null, null).items().size());
        var exact = lifecycle.ownHistory(0, 1L, "PAID", null, null).items().getFirst();
        assertEquals("1", exact.booking().getId());
        assertEquals("SGU-TEST", exact.tourCode());
        assertEquals("PAID", exact.invoiceStatus());
        when(access.accountId()).thenReturn(8);
        assertTrue(lifecycle.ownHistory(0, 1L, null, null, null).items().isEmpty());
    }

    @Test void cancelledAttemptCanBeReplacedOnlyWithASeparateRequest() {
        var original = request();
        original.setClientKey(UUID.randomUUID().toString());
        var created = lifecycle.create(original);
        int id = Integer.parseInt(created.getId());
        lifecycle.cancel(id);
        var edited = request();
        edited.setClientKey(original.getClientKey());
        edited.getListOfCustomers().getFirst().setFullName("New guest");
        var closed = lifecycle.create(edited);
        assertTrue(closed.isRestartAllowed());
        assertEquals(created.getId(), closed.getId());
        assertEquals(10, db.seats());
        assertEquals(1, db.count("booking"));

        db.jdbc.update("INSERT INTO invoice (booking_id,status) VALUES (?,'PAID')", id);
        assertThrows(ResponseStatusException.class, () -> lifecycle.create(edited));
        db.jdbc.update("DELETE FROM invoice WHERE booking_id=?", id);
        edited.setClientKey(UUID.randomUUID().toString());
        assertNotEquals(created.getId(), lifecycle.create(edited).getId());
        assertEquals(9, db.seats());
    }

    @Test void previousVietnamPricingDateCannotCreateAnOrderButAllowsReplayingOne() {
        var original = request();
        original.setPricingDate(LocalDate.now(clock));
        original.setClientKey(UUID.randomUUID().toString());
        var created = lifecycle.create(original);
        clock.set(LocalDateTime.now(clock).plusDays(1));
        assertEquals(created.getId(), lifecycle.create(original).getId());
        original.setClientKey(UUID.randomUUID().toString());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> lifecycle.create(original)).getStatusCode().value());
        assertEquals(1, db.count("booking"));
        verify(publisher, times(1)).sendNow(anyString());
        assertEquals(9, db.seats());
    }
    @Test void holdsSeatsAndEnqueuesAcknowledgementAtomically() {
        int id = create(); assertEquals(9, db.seats());
        verify(publisher, times(1)).sendNow(anyString());
        var booking = lifecycle.get(id); assertEquals("HELD", booking.getReservationStatus());
        assertEquals("PENDING_PAYMENT", booking.getStatus());
        assertEquals("3350000", booking.getTotalAmount());
        assertEquals("VND", booking.getCurrency());
        assertEquals("3350000.00", booking.getUnitPrices().get("ADULT"));
        assertEquals("2026-11-07T08:00", booking.getPaymentDeadline());
        assertTrue(booking.getCreatedAt().startsWith("2026-11-01T10:00"));
        var payload = captureSendNow();
        assertNotNull(payload);
        assertTrue(payload.contains("bookingId"));
        assertTrue(payload.contains("eventType"));
    }
    @Test void sameRequestReplaysOriginalFareAfterBirthdayAtVietnamMidnight() {
        clock.set(LocalDateTime.of(2026, 11, 1, 23, 59, 59));
        var booking = request();
        booking.setClientKey(UUID.randomUUID().toString());
        booking.getListOfCustomers().getFirst().setBirthdate("2014-11-02");
        var original = lifecycle.create(booking);
        assertEquals("CHILD", db.jdbc.queryForObject("SELECT booking_type FROM customer", String.class));

        clock.set(LocalDateTime.of(2026, 11, 2, 0, 0));
        booking.getListOfCustomers().getFirst().setFullName(" Danh ");
        booking.getListOfCustomers().getFirst().setGender("male");
        var replay = lifecycle.create(booking);
        assertEquals(original.getId(), replay.getId());
        assertEquals(original.getTotalAmount(), replay.getTotalAmount());
        assertEquals(1, db.count("booking"));
        assertEquals(9, db.seats());
        verify(publisher, times(1)).sendNow(anyString());

        booking.setClientKey(UUID.randomUUID().toString());
        lifecycle.create(booking);
        assertEquals("ADULT", db.jdbc.queryForObject("SELECT booking_type FROM customer ORDER BY id DESC LIMIT 1", String.class));
    }
    @Test void keyCannotReplayWithDifferentPassengersOrDeparture() {
        var booking = request();
        booking.setClientKey(UUID.randomUUID().toString());
        lifecycle.create(booking);

        booking.getListOfCustomers().getFirst().setFullName("Khách khác");
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> lifecycle.create(booking)).getStatusCode().value());
        booking.getListOfCustomers().getFirst().setFullName("Danh");
        db.jdbc.update("INSERT INTO tour_departure (id,tour_id,departure_date,available_seats) VALUES (51,1,?,10)",
                LocalDateTime.of(2026, 11, 21, 8, 0));
        booking.setTourDepartureId("51");
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> lifecycle.create(booking)).getStatusCode().value());

        assertEquals(1, db.count("booking"));
        assertEquals(1, db.count("customer"));
        verify(publisher, times(1)).sendNow(anyString());
        assertEquals(9, db.seats());
        assertEquals(10, db.jdbc.queryForObject("SELECT available_seats FROM tour_departure WHERE id=51", Integer.class));
    }
    @Test void immediateCheckoutHasNoPrematureSuccessEmail() {
        clock.set(LocalDateTime.of(2026,11,13,10,0)); int id = create();
        assertTrue(lifecycle.get(id).isPaymentRequiredNow()); assertEquals("CHECKOUT", lifecycle.get(id).getReservationStatus());
        verify(publisher, never()).sendNow(anyString()); assertEquals(9, db.seats());
    }
    @Test void cancellationIsIdempotentAndPreservesOriginalCreatedAt() {
        int id = create(); var original = lifecycle.get(id).getCreatedAt(); clock.set(LocalDateTime.of(2026,11,2,10,0));
        lifecycle.cancel(id); lifecycle.cancel(id); assertEquals(10, db.seats());
        assertEquals(original, lifecycle.get(id).getCreatedAt()); verify(publisher, times(2)).sendNow(anyString());
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
        verify(publisher, times(2)).sendNow(anyString()); assertNotEquals(id, create()); assertEquals(9, db.seats());
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
        doThrow(new RuntimeException("Mail provider unavailable")).when(publisher).sendNow(anyString());
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
        assertEquals(10, db.seats()); verify(publisher, times(2)).sendNow(anyString());
    }
    @Test void migrationCanBeImportedAgainWithoutChangingSeats() throws Exception {
        create(); db.run("db/003_booking_reservation.sql", System.getenv("LIFECYCLE_TEST_JDBC_URL") != null);
        assertEquals(9, db.seats()); assertEquals(1, db.count("booking_reservation"));
    }
    @Test void lockedAccountCannotCreateBookingOrConsumeSeats(){
        db.jdbc.update("UPDATE users SET status='LOCKED' WHERE id=7");
        when(authClient.getUserStatus(anyLong())).thenReturn(Map.of("status", "LOCKED"));
        assertThrows(ResponseStatusException.class,()->create());assertEquals(10,db.seats());assertEquals(0,db.count("booking"));
        verify(publisher, never()).sendNow(anyString());
    }
    @Test void ownListStaysScopedWhenTheAccountMayReadEveryone() {
        int own = create();
        db.jdbc.update("INSERT INTO booking (account_id,tour_departure_id,status,created_at) VALUES (8,1,'CONFIRMED',?)", LocalDateTime.now(clock));
        when(access.can("BOOKINGS_GET_ALL")).thenReturn(true);
        assertEquals(List.of(String.valueOf(own)),
                lifecycle.list(0, 10, true).stream().map(com.example.booking_service.dto.response.BookingResponse::getId).toList());
        assertEquals(2, lifecycle.list(0, 10, false).size());
    }
    @Test void poisonDepartureDoesNotStarveLaterDepartures() {
        int first = create();
        db.jdbc.update("INSERT INTO tour_departure (id,tour_id,departure_date,available_seats) VALUES (51,1,?,10)", LocalDateTime.of(2026,11,20,6,0));
        var other = request(); other.setTourDepartureId("51");
        int later = Integer.parseInt(lifecycle.create(other).getId());
        db.jdbc.execute("ALTER TABLE tour_departure ADD COLUMN poison_expiry BOOLEAN NOT NULL DEFAULT FALSE");
        db.jdbc.update("UPDATE tour_departure SET poison_expiry=TRUE WHERE id=1");
        db.jdbc.execute("ALTER TABLE tour_departure ADD CONSTRAINT poison_first CHECK (NOT poison_expiry OR version<2)");
        clock.set(LocalDateTime.of(2026,11,8,8,0));
        expiry.sweep();
        assertEquals("HELD", db.jdbc.queryForObject("SELECT status FROM booking_reservation WHERE booking_id=?", String.class, first));
        assertEquals("EXPIRED", db.jdbc.queryForObject("SELECT status FROM booking_reservation WHERE booking_id=?", String.class, later));
        assertEquals(10, db.jdbc.queryForObject("SELECT available_seats FROM tour_departure WHERE id=51", Integer.class));
    }
}