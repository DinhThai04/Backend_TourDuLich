package com.example.booking_service.advice;

import com.example.booking_service.reservation.*;
import org.junit.jupiter.api.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BookingAdviceServiceTest {
    LifecycleTestDatabase db;
    LifecycleTestDatabase.MutableClock clock;
    BookingAdviceService service;
    LocalDateTime created=LocalDateTime.of(2026,11,1,10,0);
    @BeforeEach void setup() throws Exception {
        db=new LifecycleTestDatabase();clock=new LifecycleTestDatabase.MutableClock(LocalDateTime.of(2026,11,4,8,0));
        db.jdbc.execute("ALTER TABLE tour ADD tour_program VARCHAR(30)");db.jdbc.update("UPDATE tour SET tour_program='TOUR_01'");
        service=new BookingAdviceService(db.jdbc,new BookingAccess(),clock);
        login(7,false);booking(6,7,"HELD");booking(7,8,"HELD");
    }
    @AfterEach void logout() { SecurityContextHolder.clearContext(); }
    void login(int id,boolean admin) {
        var jwt=Jwt.withTokenValue("fixture-only").header("alg","HS512").subject(""+id).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,admin?List.of(new SimpleGrantedAuthority("SGU_SUPER_ADMIN")):List.of()));
    }
    void booking(int id,int owner,String hold) {
        db.jdbc.update("INSERT INTO booking (id,account_id,tour_departure_id,status,created_at) VALUES (?,?,1,'CONFIRMED',?)",id,owner,created);
        if(hold!=null) db.jdbc.update("INSERT INTO booking_reservation (booking_id,departure_id,departure_at,seats,status,expires_at,created_at,updated_at) VALUES (?,1,?,1,?,?,?,?)",
                id,LocalDateTime.of(2026,11,20,6,0),hold,LocalDateTime.of(2026,11,7,8,0),created,created);
    }
    @Test void ownerPredicateAppliesEvenToAdminsAndCallerCannotChooseOtherAccount() {
        assertEquals(List.of(6),service.mine(null).bookings().stream().map(BookingAdviceService.Item::bookingId).toList());
        assertTrue(service.mine(7).bookings().isEmpty());login(7,true);assertTrue(service.mine(7).bookings().isEmpty());
        login(8,false);assertEquals(7,service.mine(null).bookings().getFirst().bookingId());
    }
    @Test void anonymousAndInvalidIdFailBeforeDatabaseRead() {
        logout();assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.mine(null));
        login(7,false);assertThrows(IllegalArgumentException.class,()->service.mine(-1));
    }
    @Test void remainingTimeUsesStoredDeadlineAndVietnamZone() {
        var b=service.mine(6).bookings().getFirst();assertEquals("HELD",b.state());assertEquals(259200,b.remainingSeconds());
        assertEquals(ZoneOffset.ofHours(7),b.paymentDeadline().getOffset());
        db.jdbc.update("UPDATE booking_reservation SET expires_at=? WHERE booking_id=6",LocalDateTime.of(2026,11,5,9,15));
        assertEquals(90900,service.mine(6).bookings().getFirst().remainingSeconds()); // Stored, not recomputed +6 days.
    }
    @Test void overdueReadDoesNotSweepReleaseSeatsOrCreateMail() {
        clock.set(LocalDateTime.of(2026,11,7,8,0));var b=service.mine(6).bookings().getFirst();
        assertEquals("DEADLINE_PASSED",b.state());assertEquals(0,b.remainingSeconds());assertEquals(10,db.seats());
        assertEquals("HELD",db.jdbc.queryForObject("SELECT status FROM booking_reservation WHERE booking_id=6",String.class));
        assertEquals(0,db.count("mail_outbox"));
    }
    @Test void legacyDoesNotReceiveInventedDeadlineAndCheckoutHasShortRemainingTime() {
        booking(8,7,null);assertEquals("LEGACY_UNVERIFIED",service.mine(8).bookings().getFirst().state());
        db.jdbc.update("UPDATE booking_reservation SET status='CHECKOUT',expires_at=? WHERE booking_id=6",LocalDateTime.of(2026,11,4,8,15));
        var b=service.mine(6).bookings().getFirst();assertEquals("CHECKOUT",b.state());assertEquals(900,b.remainingSeconds());
    }
    @Test void partialPaymentEvidenceNeedsReviewAndCompleteRecordsStopCountdown() {
        db.jdbc.update("INSERT INTO payment (booking_id,account_id,status,total_booking_tour_expense) VALUES (6,7,'PAID',1000000)");
        assertEquals("REVIEW_REQUIRED",service.mine(6).bookings().getFirst().state());
        db.jdbc.update("INSERT INTO invoice (booking_id,account_id,status,total_booking_tour_expense) VALUES (6,7,'PAID',1000000)");
        assertEquals("REVIEW_REQUIRED",service.mine(6).bookings().getFirst().state());
        db.jdbc.update("UPDATE booking_reservation SET status='PAID' WHERE booking_id=6");
        assertEquals("PAID",service.mine(6).bookings().getFirst().state());assertEquals(0,service.mine(6).bookings().getFirst().remainingSeconds());
        db.jdbc.update("UPDATE invoice SET total_booking_tour_expense=1000001 WHERE booking_id=6");
        assertEquals("REVIEW_REQUIRED",service.mine(6).bookings().getFirst().state());
    }
    @Test void cancelledExpiredAndRescheduledDeparturesAreNotAvailableHolds() {
        db.jdbc.update("UPDATE booking_reservation SET status='EXPIRED' WHERE booking_id=6");assertEquals("EXPIRED",service.mine(6).bookings().getFirst().state());
        db.jdbc.update("UPDATE booking_reservation SET status='CANCELLED' WHERE booking_id=6");assertEquals("CANCELLED",service.mine(6).bookings().getFirst().state());
        db.jdbc.update("UPDATE booking_reservation SET status='HELD' WHERE booking_id=6");db.jdbc.update("UPDATE tour_departure SET departure_date=? WHERE id=1",LocalDateTime.of(2026,11,21,6,0));
        assertEquals("REVIEW_REQUIRED",service.mine(6).bookings().getFirst().state());
    }
    @Test void listIsBoundedButExplicitOwnIdCanReachOlderBooking() {
        for(int i=20;i<43;i++) booking(i,7,null);
        var page=service.mine(null);assertEquals(20,page.bookings().size());assertTrue(page.hasMore());
        assertEquals(6,service.mine(6).bookings().getFirst().bookingId());
    }
    @Test void pausedDepartureNeverReportsAnExpiredPaymentDeadlineAsValidHold() {
        db.jdbc.update("UPDATE tour_departure SET operational_status='SUSPENDED'");
        clock.set(LocalDateTime.of(2026,11,8,10,0));var b=service.mine(6).bookings().getFirst();
        assertEquals("SCHEDULE_CHANGE_REQUIRED",b.state());assertEquals(0,b.remainingSeconds());
        assertEquals(0,db.count("mail_outbox"));
    }
}
