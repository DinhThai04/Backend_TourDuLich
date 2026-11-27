package com.example.booking_service.reservation;

import com.example.booking_service.advice.ReviewEligibilityService;
import com.example.booking_service.repository.httpclient.AuthInternalClient;
import com.example.booking_service.repository.httpclient.InvoiceInternalClient;
import com.example.booking_service.repository.httpclient.PaymentInternalClient;
import com.example.booking_service.repository.httpclient.TourInternalClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Map;

import static com.example.booking_service.reservation.DepartureManifestService.Attendance.*;
import static com.example.booking_service.reservation.DepartureManifestService.Phase.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DepartureManifestServiceTest {
    private final LocalDateTime departure = LocalDateTime.of(2026, 11, 20, 6, 0);
    private final LocalDateTime returns = departure.plusDays(1);
    private final LifecycleTestDatabase.MutableClock clock = new LifecycleTestDatabase.MutableClock(departure.minusHours(2));
    private LifecycleTestDatabase db;
    private BookingAccess access;
    private TourInternalClient tours;
    private AuthInternalClient accounts;
    private DepartureManifestService service;
    private InvoiceInternalClient invoices;
    private com.example.booking_service.operations.AdminAuditEvents audit;

    @BeforeEach void setup() throws Exception {
        db = new LifecycleTestDatabase();
        access = mock(BookingAccess.class);
        tours = mock(TourInternalClient.class);
        when(tours.getDeparture(1)).thenReturn(Map.of(
                "departure_date", departure.toString(), "return_date", returns.toString(), "operational_status", "OPEN", "tour_id", 1));
        when(access.admin()).thenReturn(true);
        when(access.accountId()).thenReturn(7);
        accounts = mock(AuthInternalClient.class);
        invoices = mock(InvoiceInternalClient.class);
        audit = mock(com.example.booking_service.operations.AdminAuditEvents.class);
        service = new DepartureManifestService(db.jdbc, access, tours, clock, accounts, audit, invoices);
        db.jdbc.update("INSERT INTO booking(id,account_id,tour_departure_id,status,created_at) VALUES(1,7,1,'CONFIRMED',?)", departure.minusDays(1));
        db.jdbc.update("""
                INSERT INTO booking_reservation(booking_id,departure_id,departure_at,seats,status,expires_at,created_at,updated_at)
                VALUES(1,1,?,2,'PAID',?,?,?)
                """, departure, departure, departure, departure);
        db.jdbc.update("""
                INSERT INTO customer(id,booking_id,full_name,birthdate,gender,booking_type,status)
                VALUES(1,1,'An','2000-01-01',1,'ADULT','BOOKED'),(2,1,'Bình','2000-01-01',1,'ADULT','BOOKED')
                """);
    }

    @Test void elapsedScheduleNeedsExplicitCloseoutAndRecordsAttendance() {
        assertEquals(SCHEDULED, service.get(1).phase());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> mark(1, BOARDED)).getStatusCode().value());
        clock.set(departure.minusMinutes(90));
        assertEquals(BOARDING, service.get(1).phase());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> mark(2, NO_SHOW)).getStatusCode().value());
        assertEquals(BOARDED, mark(1, BOARDED).passengers().getFirst().status());
        mark(1, BOARDED);
        assertEquals(1, db.count("departure_attendance"));

        clock.set(departure);
        confirm();
        clock.set(returns.plusHours(1));
        assertEquals(AWAITING_CLOSEOUT, service.get(1).phase());
        assertEquals(409, assertThrows(ResponseStatusException.class, this::complete).getStatusCode().value());
        mark(2, NO_SHOW);
        mark(1, RETURNED);
        var result = complete();
        assertEquals(COMPLETED, result.phase());
        assertEquals(7, result.completedBy());
        assertEquals(result.completedAt(), complete().completedAt());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> mark(2, BOARDED)).getStatusCode().value());
        assertEquals("CONFIRMED", db.jdbc.queryForObject("SELECT status FROM booking WHERE id=1", String.class));
    }

    @Test void elapsedDepartureStaysUnconfirmedEvenWhenPickupAndGuideAreMissing() {
        clock.set(departure.plusHours(2));
        var result = service.get(1);
        assertEquals(OVERDUE_UNCONFIRMED, result.phase());
        assertNull(result.guideAccountId());
        assertNull(result.passengers().getFirst().meetingPointName());
        assertEquals(EXPECTED, result.passengers().getFirst().status());
        assertEquals(0, db.count("departure_attendance"));

        when(access.admin()).thenReturn(false);
        when(access.accountId()).thenReturn(8);
        assertTrue(service.assignedDepartures().isEmpty());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> service.get(1)).getStatusCode().value());
        when(access.admin()).thenReturn(true);
        when(access.accountId()).thenReturn(7);
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> mark(1, BOARDED)).getStatusCode().value());
        clock.set(returns.plusHours(2));
        assertEquals(OVERDUE_UNCONFIRMED, service.get(1).phase());
        assertEquals(409, assertThrows(ResponseStatusException.class, this::complete).getStatusCode().value());
    }

    @Test void verifiesLegacyBookingAgainstPaidInvoiceWithoutRecreatingSeatHolds() {
        db.jdbc.update("DELETE FROM booking_reservation WHERE booking_id=1");
        assertTrue(service.get(1).passengers().isEmpty());
        when(invoices.getInvoicesBatch(java.util.List.of(1))).thenReturn(java.util.List.of(Map.of(
                "bookingId", 1, "status", "PAID")));
        assertEquals(2, service.get(1).passengers().size());
        assertEquals(0, db.count("booking_reservation"));
    }

    @Test void rejectsEarlyOrUnexplainedConfirmationAndAuditsRetroactiveDepartureOnce() {
        assertEquals(409, assertThrows(ResponseStatusException.class, this::confirm).getStatusCode().value());
        clock.set(departure.plusHours(2));
        assertEquals(409, assertThrows(ResponseStatusException.class, this::confirm).getStatusCode().value());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> db.tx.execute(tx ->
                service.confirmDeparture(1, departure, " "))).getStatusCode().value());
        var confirmed = db.tx.execute(tx -> service.confirmDeparture(1, departure, "Xe đã rời điểm đón"));
        assertEquals(IN_PROGRESS, confirmed.phase());
        assertEquals(departure, confirmed.actualDepartureAt());
        assertEquals(7, confirmed.confirmedBy());
        assertEquals(departure, confirm().actualDepartureAt());
        verify(audit).record(eq(7), eq("DEPARTURE_CONFIRMED"), eq("DEPARTURE"), eq(1), anyString(),
                anyString(), eq("Xe đã rời điểm đón"), anyMap(), anyMap());
    }

    @Test void rejectsNonAdminBeforeLoadingSchedule() {
        when(access.admin()).thenReturn(false);
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> service.get(1)).getStatusCode().value());
        verifyNoInteractions(tours);
    }

    @Test void guideOnlyOperatesAssignedDepartureAndLosesAccessOnRemoval() {
        when(accounts.getUserStatus(8L)).thenReturn(Map.of("status", "ACTIVE", "tourGuide", false));
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> db.tx.execute(transaction -> service.assign(1, 8))).getStatusCode().value());
        when(accounts.getUserStatus(8L)).thenReturn(Map.of("status", "ACTIVE", "tourGuide", true));
        assertEquals(8, db.tx.execute(transaction -> service.assign(1, 8)).guideAccountId());
        when(access.admin()).thenReturn(false);
        when(access.accountId()).thenReturn(8);
        assertEquals(java.util.List.of(1), service.assignedDepartures());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> service.get(2)).getStatusCode().value());
        clock.set(departure);
        assertEquals(8, confirm().confirmedBy());
        assertEquals(BOARDED, mark(1, BOARDED).passengers().getFirst().status());
        assertEquals(403, assertThrows(ResponseStatusException.class, this::complete).getStatusCode().value());
        when(access.admin()).thenReturn(true);
        db.tx.execute(transaction -> service.assign(1, null));
        when(access.admin()).thenReturn(false);
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> mark(1, NO_SHOW)).getStatusCode().value());
    }

    @Test void reviewRequiresRecordedReturnAndCloseout() {
        var auth = mock(AuthInternalClient.class);
        var payment = mock(PaymentInternalClient.class);
        var invoice = mock(InvoiceInternalClient.class);
        when(auth.getUserById(7L)).thenReturn(Map.of("email", "guest@example.test", "username", "guest"));
        when(payment.getBookingPaymentEvidence(1)).thenReturn(Map.of("paidPayment", true));
        var paid = Map.<String, Object>of("status", "PAID", "totalBookingTourExpense", 500000);
        when(payment.getPaymentStatus(1)).thenReturn(paid);
        when(invoice.getInvoiceByBookingId(1)).thenReturn(paid);
        var review = new ReviewEligibilityService(db.jdbc, access, tours, auth, payment, invoice);
        clock.set(departure);
        confirm();
        clearInvocations(tours);
        clock.set(returns.plusHours(1));
        assertFalse(review.check(1).eligible());
        mark(1, BOARDED);
        mark(1, RETURNED);
        mark(2, NO_SHOW);
        assertFalse(review.check(1).eligible());
        verify(tours, times(3)).getDeparture(1);
        complete();
        assertTrue(review.check(1).eligible());
        db.jdbc.update("UPDATE departure_attendance SET status='NO_SHOW' WHERE customer_id=1");
        assertFalse(review.check(1).eligible());
    }

    @Test void allAbsentCanCloseAfterReportIsApproved() {
        when(accounts.getUserStatus(8L)).thenReturn(Map.of("status", "ACTIVE", "tourGuide", true));
        db.tx.execute(transaction -> service.assign(1, 8));
        clock.set(departure);
        confirm();
        clock.set(returns.plusHours(1));
        mark(1, NO_SHOW);
        mark(2, NO_SHOW);
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> db.tx.execute(transaction -> service.complete(1, "Chốt"))).getStatusCode().value());
        when(access.admin()).thenReturn(false);
        when(access.accountId()).thenReturn(8);
        var submitted = db.tx.execute(transaction -> service.saveReport(1, true, "Toàn bộ khách vắng", null, "Không có khách bàn giao"));
        assertEquals("SUBMITTED", submitted.status());
        when(access.admin()).thenReturn(true);
        when(access.accountId()).thenReturn(7);
        db.tx.execute(transaction -> service.review(1, true, null));
        assertEquals("COMPLETED_NO_SHOW", db.tx.execute(transaction -> service.complete(1, "Đã duyệt")).outcome());
    }

    @Test void overlappingAssignmentRequiresExplicitOverrideAndReason() {
        when(accounts.getUserStatus(8L)).thenReturn(Map.of("status", "ACTIVE", "tourGuide", true));
        when(tours.getDeparture(2)).thenReturn(Map.of("departure_date", departure.plusHours(1).toString(),
                "return_date", returns.plusHours(1).toString(), "operational_status", "OPEN"));
        db.tx.execute(transaction -> service.assign(1, 8));
        assertEquals(Map.of(8, 1), service.guideConflicts(2, java.util.List.of(8)));
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> db.tx.execute(transaction -> service.assign(2, 8))).getStatusCode().value());
        assertEquals(8, db.tx.execute(transaction -> service.assign(2, 8, true, "Có phương án bàn giao")).guideAccountId());
    }

    @Test void guideMustExplainDelayedCheckpoint() {
        when(accounts.getUserStatus(8L)).thenReturn(Map.of("status", "ACTIVE", "tourGuide", true));
        when(tours.getItinerary(1)).thenReturn(java.util.List.of(Map.of("id", 4, "day", 1, "title", "Tham quan")));
        db.tx.execute(transaction -> service.assign(1, 8));
        when(access.admin()).thenReturn(false);
        when(access.accountId()).thenReturn(8);
        clock.set(departure);
        confirm();
        clock.set(departure.plusHours(1));
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> db.tx.execute(transaction -> service.progress(1, 4, DepartureManifestService.Progress.DELAYED, " "))).getStatusCode().value());
        assertEquals("Mưa", db.tx.execute(transaction ->
                service.progress(1, 4, DepartureManifestService.Progress.DELAYED, "Mưa")).getFirst().note());
    }

    private DepartureManifestService.Manifest confirm() {
        return db.tx.execute(transaction -> service.confirmDeparture(1, null, null));
    }

    private DepartureManifestService.Manifest mark(int customerId, DepartureManifestService.Attendance status) {
        return db.tx.execute(transaction -> service.mark(1, customerId, status));
    }

    private DepartureManifestService.Manifest complete() {
        return db.tx.execute(transaction -> service.complete(1, "Xác nhận xe và khách đã về", true));
    }
}
