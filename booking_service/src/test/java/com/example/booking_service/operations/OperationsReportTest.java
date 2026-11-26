package com.example.booking_service.operations;

import com.example.booking_service.repository.httpclient.PaymentInternalClient;
import com.example.booking_service.repository.httpclient.TourInternalClient;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OperationsReportTest {
    @Test
    void annualReportUsesSourceRecordsAndScopesTotalsAndChartByDestination() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"));
        jdbc.execute("CREATE TABLE booking(id INTEGER, status VARCHAR, created_at TIMESTAMP, tour_departure_id INTEGER)");
        jdbc.execute("CREATE TABLE booking_reservation(booking_id INTEGER, status VARCHAR)");
        jdbc.execute("CREATE TABLE customer(booking_id INTEGER)");
        jdbc.execute("CREATE TABLE booking_change(booking_id INTEGER, status VARCHAR, updated_at TIMESTAMP, refund_amount DECIMAL)");
        jdbc.execute("INSERT INTO booking VALUES (1,'CONFIRMED','2026-01-09 10:00:00',10),(2,'EXPIRED','2026-02-09 10:00:00',20),(4,'CONFIRMED','2025-12-01 10:00:00',20)");
        jdbc.execute("INSERT INTO booking_reservation VALUES (1,'PAID')");
        jdbc.execute("INSERT INTO customer VALUES (1),(1),(2)");
        jdbc.execute("INSERT INTO booking_change VALUES (1,'REFUNDED','2026-03-10 10:00:00',80)");
        var tours = mock(TourInternalClient.class);
        when(tours.reportDepartures()).thenReturn(List.of(
                Map.of("id", 10, "tourId", 1, "title", "Bắc", "region", "NORTH",
                        "departure", "2026-02-11T09:00", "returns", "2026-02-12T09:00"),
                Map.of("id", 20, "tourId", 2, "title", "Nam", "region", "SOUTH",
                        "departure", "2026-03-15T09:00", "returns", "2026-03-16T09:00")));
        var payments = mock(PaymentInternalClient.class);
        when(payments.reportAmounts("2026-01-01", "2026-12-31")).thenReturn(List.of(
                Map.of("booking_id", 1, "day", "2026-01-10", "amount", 500, "metric", "collected"),
                Map.of("booking_id", 2, "day", "2026-02-10", "amount", 800, "metric", "collected"),
                Map.of("booking_id", 3, "day", "2026-02-10", "amount", 900, "metric", "collected")));
        when(payments.settlements(List.of(4))).thenReturn(List.of(Map.of("bookingId", 4, "status", "PAID")));
        var report = new OperationsReport(jdbc, tours, payments,
                Clock.fixed(Instant.parse("2026-11-26T00:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh")));
        var from = LocalDate.parse("2026-01-01");
        var through = LocalDate.parse("2026-12-31");

        var all = report.read(from, through, null, null);
        assertEquals(2L, all.get("bookings"));
        assertEquals(2200L, all.get("collected"));
        assertEquals(2L, all.get("completed"));
        assertEquals("MONTH", all.get("granularity"));
        assertEquals(12, ((List<?>) all.get("series")).size());

        var north = report.read(from, through, 1, "NORTH");
        assertEquals(1L, north.get("bookings"));
        assertEquals(1L, north.get("completed"));
        assertEquals(2L, north.get("confirmed_passengers"));
        assertEquals(500L, north.get("collected"));
        assertEquals(80L, north.get("schedule_refunds"));
        assertThrows(ResponseStatusException.class, () -> report.read(from, through.plusDays(1), null, null));
    }
}
