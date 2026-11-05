package com.example.booking_service.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MailEligibilityTest {
    JdbcTemplate jdbc;
    MailEligibility eligibility;
    Map<String, Object> booking;
    LocalDateTime departure = LocalDateTime.of(2026,11,20,6,0);
    @BeforeEach void setup() {
        jdbc = mock(JdbcTemplate.class);
        eligibility = new MailEligibility(jdbc, Clock.fixed(Instant.parse("2026-10-14T01:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh")));
        booking = new HashMap<>(Map.of("status", "CONFIRMED", "account_id", 2, "tour_departure_id", 15,
                "departure_date", departure, "email", "tester@example.com"));
        when(jdbc.queryForList(anyString(), eq(42))).thenReturn(List.of(booking));
        when(jdbc.queryForObject(contains("FROM payment"), eq(Integer.class), eq(42), eq(2))).thenReturn(0);
        when(jdbc.queryForObject(contains("FROM invoice"), eq(Integer.class), eq(42), eq(2))).thenReturn(0);
    }
    MailOutboxItem item(MailEventType type) { return new MailOutboxItem(1, type, 42, 2, "tester@example.com", "{}", 0, "claim"); }
    MailPayload payload() { return new MailPayload(1, "Danh", "Đà Nẵng", 15, departure, 1, null, null, null, null, null); }
    @Test void testMailDoesNotNeedBusinessTables() {
        assertNull(eligibility.skipReason(item(MailEventType.TEST_MAIL), MailPayload.test())); verifyNoInteractions(jdbc);
    }
    @Test void unpaidActiveAcknowledgementIsAllowed() {
        assertNull(eligibility.skipReason(item(MailEventType.BOOKING_CREATED), payload()));
    }
    @Test void paidBookingDoesNotReceiveStaleUnpaidAcknowledgement() {
        when(jdbc.queryForObject(contains("FROM payment"), eq(Integer.class), eq(42), eq(2))).thenReturn(1);
        when(jdbc.queryForObject(contains("FROM invoice"), eq(Integer.class), eq(42), eq(2))).thenReturn(1);
        assertNotNull(eligibility.skipReason(item(MailEventType.BOOKING_CREATED), payload()));
    }
    @Test void inconsistentPaymentNeedsReconciliation() {
        when(jdbc.queryForObject(contains("FROM invoice"), eq(Integer.class), eq(42), eq(2))).thenReturn(1);
        assertTrue(eligibility.skipReason(item(MailEventType.BOOKING_CREATED), payload()).contains("reconciliation"));
    }
    @Test void cancelledBookingDoesNotReceiveUnpaidAcknowledgement() {
        booking.put("status", "CANCELLED");
        assertNotNull(eligibility.skipReason(item(MailEventType.BOOKING_CREATED), payload()));
        assertNull(eligibility.skipReason(item(MailEventType.BOOKING_CANCELLED), payload()));
    }
    @Test void changedDepartureOrRecipientInvalidatesOldMail() {
        booking.put("departure_date", departure.plusDays(1));
        assertTrue(eligibility.skipReason(item(MailEventType.BOOKING_CREATED), payload()).contains("date changed"));
        booking.put("email", "different@gmail.com");
        assertTrue(eligibility.skipReason(item(MailEventType.BOOKING_CREATED), payload()).contains("email changed"));
    }
    @Test void reminderForMissingBookingIsSkipped() {
        when(jdbc.queryForList(contains("r.reminder_one_at"),eq(42))).thenReturn(List.of());
        assertTrue(eligibility.skipReason(item(MailEventType.PAYMENT_REMINDER), payload()).contains("no longer exists"));
    }
    @Test void momoReceiptIsEligibleWhenTheMomoTransactionIsPaid() {
        booking.put("operational_status", "OPEN");
        when(jdbc.queryForObject(contains("FROM payment"), eq(Integer.class), eq(42), eq(2))).thenReturn(1);
        when(jdbc.queryForObject(contains("FROM invoice"), eq(Integer.class), eq(42), eq(2))).thenReturn(1);
        when(jdbc.queryForObject(contains("momo_transaction"), eq(Integer.class),
                any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        var paid = new MailPayload(1, "Danh", "Đà Nẵng", 15, departure, 1,
                new java.math.BigDecimal("3350000"), "M123", departure.minusDays(1), null, null);
        assertNull(eligibility.skipReason(item(MailEventType.PAYMENT_SUCCEEDED), paid));
    }
}
