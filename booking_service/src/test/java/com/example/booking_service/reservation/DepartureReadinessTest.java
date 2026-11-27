package com.example.booking_service.reservation;

import com.example.booking_service.repository.httpclient.TourInternalClient;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.example.booking_service.reservation.DepartureReadiness.Issue.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DepartureReadinessTest {
    @Test void surfacesOperationalWarningsAndClearsResolvedPickup() throws Exception {
        var db = new LifecycleTestDatabase();
        var time = LocalDateTime.of(2026, 11, 27, 8, 0);
        var clock = new LifecycleTestDatabase.MutableClock(time.plusHours(1));
        var access = mock(BookingAccess.class);
        var tours = mock(TourInternalClient.class);
        var manifests = mock(DepartureManifestService.class);
        var readiness = new DepartureReadiness(db.jdbc, access, tours, manifests, clock);
        assertEquals(403, assertThrows(ResponseStatusException.class, () ->
                readiness.alerts(0, 20, null, null)).getStatusCode().value());
        when(access.admin()).thenReturn(true);
        when(tours.reportDepartures()).thenReturn(List.of(
                row(1, time, 1), row(2, time.plusDays(1), 0), row(3, time.plusDays(8), 0)));
        String revision = "b".repeat(64);
        when(tours.getDeparture(1)).thenReturn(Map.of("meeting_points", List.of(Map.of(
                "confirmationKey", revision, "address", "Điểm mới", "arrivalTime", "07:00", "revisionKey", "new"))));
        db.jdbc.update("INSERT INTO booking(id,account_id,tour_departure_id,status) VALUES(10,7,1,'CONFIRMED'),(11,7,1,'CONFIRMED')");
        db.jdbc.update("INSERT INTO customer(booking_id,booking_type) VALUES(10,'ADULT'),(11,'ADULT')");
        db.jdbc.update("""
                INSERT INTO booking_pickup_snapshot(booking_id,departure_id,address,arrival_time,meeting_point_id)
                VALUES(11,1,'Điểm cũ','07:30:00',?)
                """, "a".repeat(64));
        when(manifests.paidBookings(1)).thenReturn(Set.of(10, 11));

        var result = readiness.alerts(0, 20, null, null);
        assertTrue(result.items().stream().map(DepartureReadiness.Alert::issue).collect(java.util.stream.Collectors.toSet())
                .containsAll(Set.of(OVERDUE_UNCONFIRMED, MISSING_GUIDE, MISSING_VEHICLE,
                        OVER_CAPACITY, MISSING_PICKUP, NEEDS_RECONFIRMATION)));
        assertTrue(readiness.alerts(0, 2, null, null).hasNext());
        assertEquals(NEEDS_RECONFIRMATION, readiness.alerts(0, 20, 11, time.toLocalDate()).items().getFirst().issue());
        db.jdbc.update("INSERT INTO departure_manifest(departure_id,actual_departure_at,confirmed_by) VALUES(1,?,7)", time);
        assertEquals(NEEDS_RECONFIRMATION, readiness.alerts(0, 20, 11, null).items().getFirst().issue());
        db.jdbc.update("UPDATE booking_pickup_snapshot SET meeting_point_id=? WHERE booking_id=11", revision);
        assertTrue(readiness.alerts(0, 20, 11, null).items().isEmpty());
        assertTrue(readiness.alerts(0, 20, null, null).items().stream().noneMatch(alert -> alert.departureId() == 1));
        assertEquals(0, db.count("departure_attendance"));
    }

    private static Map<String, Object> row(int id, LocalDateTime start, int capacity) {
        return Map.of("id", id, "tourId", 1, "code", "SGU-" + id, "title", "Tour SGU",
                "operationalStatus", "OPEN", "vehicleCapacity", capacity, "departure", start.toString());
    }
}
