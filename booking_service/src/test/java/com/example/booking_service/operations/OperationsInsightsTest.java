package com.example.booking_service.operations;

import com.example.booking_service.repository.httpclient.InvoiceInternalClient;
import com.example.booking_service.repository.httpclient.PaymentInternalClient;
import com.example.booking_service.repository.httpclient.TourInternalClient;
import com.example.booking_service.reservation.BookingAccess;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class OperationsInsightsTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final BookingAccess access = mock(BookingAccess.class);
    private final ReportingOperationsClient reporting = mock(ReportingOperationsClient.class);
    private final TourInternalClient tours = mock(TourInternalClient.class);
    private final PaymentInternalClient payments = mock(PaymentInternalClient.class);
    private final InvoiceInternalClient invoices = mock(InvoiceInternalClient.class);
    private final OperationsInsights insights = new OperationsInsights(
            jdbc, access, Clock.systemUTC(), reporting, tours, null, payments, invoices);

    @Test
    void reportingOwnsTheAdminAuditRead() {
        var result = new OperationsInsights.AuditPage(List.of(Map.of("id", 5)), true, 45);
        when(access.admin()).thenReturn(true);
        when(reporting.available()).thenReturn(true);
        when(reporting.audit(2, 20)).thenReturn(result);

        assertEquals(result, insights.audit(2, 20));
        verifyNoInteractions(jdbc);
    }

    @Test
    void integrityIncludesMissingDepartureAndToleratesMissingReservation() {
        when(jdbc.queryForList(anyString())).thenReturn(List.of(
                Map.of("id", 1, "status", "CONFIRMED"),
                Map.of("id", 2, "status", "CONFIRMED", "tour_departure_id", 14)));
        when(jdbc.queryForList(anyString(), eq(2))).thenReturn(List.of());
        when(tours.getDeparture(14)).thenReturn(Map.of("operational_status", "OPEN"));

        var result = insights.scheduledReview();
        assertEquals(List.of(Map.of(
                "booking_id", 1, "booking_status", "CONFIRMED", "issue", "MISSING_DEPARTURE"
        )), result.items());
    }
}
