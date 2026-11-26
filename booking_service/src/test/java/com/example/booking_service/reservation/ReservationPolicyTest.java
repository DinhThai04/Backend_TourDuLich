package com.example.booking_service.reservation;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class ReservationPolicyTest {
    final ReservationPolicy policy = new ReservationPolicy();

    @org.junit.jupiter.api.Test
    void saleClosesAtExactCutoffAndPaymentDeadlineCannotExceedIt() {
        var departure = java.time.LocalDateTime.of(2026, 11, 1, 12, 0);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> policy.requireBookable(departure.minusHours(4).minusSeconds(1), departure));
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> policy.requireBookable(departure.minusHours(4), departure));
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> policy.requireBookable(departure.minusHours(2), departure));
        org.junit.jupiter.api.Assertions.assertEquals(departure.minusHours(4), policy.capDeadline(departure, departure));
    }

    LocalDateTime day(int day) { return LocalDateTime.of(2026,11,day,8,0); }
    @Test void earlyBookingHasSixDayDeadlineAndTwoReminders() {
        var plan = policy.plan(day(1), day(20).withHour(6));
        assertEquals(day(7), plan.deadline()); assertEquals(day(4), plan.reminderOne()); assertEquals(day(6), plan.reminderTwo());
    }
    @Test void tenDaysBeforeDepartureHasOneReminderAndShorterDeadline() {
        var plan = policy.plan(day(10), day(20).withHour(6));
        assertEquals(day(14), plan.deadline()); assertEquals(day(13), plan.reminderOne()); assertNull(plan.reminderTwo());
    }
    @Test void eightDaysBeforeDepartureSkipsFirstReminderBeyondDeadline() {
        var plan = policy.plan(day(12), day(20).withHour(6));
        assertEquals(day(14), plan.deadline()); assertNull(plan.reminderOne()); assertEquals(day(13), plan.reminderTwo());
    }
    @Test void sevenCalendarDaysIsImmediateEvenIfMoreThan168HoursApart() {
        var plan = policy.plan(day(13).withHour(1), day(20).withHour(23));
        assertTrue(plan.immediate()); assertEquals(day(13).withHour(1).plusMinutes(15), plan.deadline());
        assertNull(plan.reminderOne()); assertNull(plan.reminderTwo());
    }
    @Test void checkoutNeverOutlivesDeparture() {
        assertEquals(day(20).plusMinutes(5), policy.plan(day(20), day(20).plusMinutes(5)).deadline());
        assertThrows(IllegalArgumentException.class, () -> policy.plan(day(20), day(20)));
    }
}
