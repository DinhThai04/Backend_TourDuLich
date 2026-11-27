package com.example.booking_service.reservation;

import com.example.booking_service.config.BookingMailOutboxPublisher;
import com.example.booking_service.config.NotificationMailClient;
import com.example.booking_service.notification.MailProperties;
import com.example.booking_service.repository.httpclient.AuthInternalClient;
import com.example.booking_service.repository.httpclient.TourInternalClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookingPickupServiceTest {
    @Test
    void changedOrRemovedStopRemainsUnconfirmedUntilAdminAssignsWithReason() throws Exception {
        var db = new LifecycleTestDatabase();
        db.jdbc.update("INSERT INTO booking(id,account_id,tour_departure_id,status) VALUES(10,7,1,'CONFIRMED')");
        var clock = new LifecycleTestDatabase.MutableClock(LocalDateTime.of(2026, 11, 20, 4, 45));
        var access = mock(BookingAccess.class);
        when(access.accountId()).thenReturn(7);
        var tours = mock(TourInternalClient.class);
        var audit = mock(com.example.booking_service.operations.AdminAuditEvents.class);
        var manifests = mock(DepartureManifestService.class);
        when(manifests.paidBookings(1)).thenReturn(java.util.Set.of(10));
        var pickups = new BookingPickupService(db.jdbc, access, tours, mock(AuthInternalClient.class),
                mock(BookingMailOutboxPublisher.class), new ObjectMapper(), new MailProperties(), clock, db.manager,
                manifests, audit);
        String old = "a".repeat(64), revised = "b".repeat(64);
        var oldPoint = Map.of("confirmationKey", old, "address", "Điểm A", "arrivalTime", "05:30", "revisionKey", "old");
        var revisedPoint = Map.of("confirmationKey", revised, "address", "Điểm A", "arrivalTime", "05:30", "revisionKey", "new");
        db.jdbc.update("""
                INSERT INTO booking_pickup_snapshot(booking_id,departure_id,address,arrival_time,meeting_point_id)
                VALUES(10,1,'Điểm A','05:30:00',?)
                """, old);
        when(tours.getDeparture(1)).thenReturn(departure(List.of(oldPoint)));
        assertEquals(BookingPickupService.PickupStatus.CONFIRMED, pickups.options(10).pickupStatus());
        when(tours.getDeparture(1)).thenReturn(departure(List.of()));
        assertEquals(BookingPickupService.PickupStatus.NEEDS_RECONFIRMATION, pickups.options(10).pickupStatus());
        when(tours.getDeparture(1)).thenReturn(departure(List.of(revisedPoint)));
        assertEquals(BookingPickupService.PickupStatus.NEEDS_RECONFIRMATION, pickups.options(10).pickupStatus());
        assertEquals(403, assertThrows(ResponseStatusException.class, () ->
                pickups.assign(10, revised, "Khách đã gọi")).getStatusCode().value());
        when(access.admin()).thenReturn(true);
        assertEquals(400, assertThrows(ResponseStatusException.class, () ->
                pickups.assign(10, revised, " ")).getStatusCode().value());
        assertThrows(ResponseStatusException.class, () -> pickups.assign(10, old, "Khách đã gọi"));
        assertFalse(pickups.options(10).editable());
        assertEquals(BookingPickupService.PickupStatus.CONFIRMED, pickups.assign(10, revised, "Khách đã gọi").pickupStatus());
        assertEquals(revised, pickups.options(10).selectedKey());
        verify(audit).record(eq(7), eq("PICKUP_ASSIGNED_BY_ADMIN"), eq("BOOKING"), eq(10), anyString(),
                anyString(), eq("Khách đã gọi"), anyMap(), anyMap());
        clock.set(LocalDateTime.of(2026, 11, 20, 5, 30));
        assertThrows(ResponseStatusException.class, () -> pickups.assign(10, revised, "Đã qua giờ có mặt"));
        assertEquals(0, db.count("departure_attendance"));
    }

    private static Map<String, Object> departure(List<Map<String, String>> points) {
        return Map.of("departure_date", "2026-11-20T06:00:00", "operational_status", "OPEN", "meeting_points", points);
    }

    @Test
    void publishesOncePerPaidBookingAndRecordsOnlyOwnersSelectionBeforeDeadline() throws Exception {
        var db = new LifecycleTestDatabase();
        db.jdbc.update("INSERT INTO booking(id, account_id, tour_departure_id, status) VALUES (10,7,1,'CONFIRMED'),(11,8,1,'PENDING_PAYMENT')");
        var clock = new LifecycleTestDatabase.MutableClock(LocalDateTime.of(2026, 11, 19, 12, 0));
        var access = mock(BookingAccess.class);
        when(access.can("TOUR_DEPARTURES_UPDATE")).thenReturn(true);
        var tours = mock(TourInternalClient.class);
        var auth = mock(AuthInternalClient.class);
        String key = "a".repeat(64);
        when(tours.getDeparture(1)).thenReturn(Map.of(
                "departure_date", "2026-11-20T06:00:00", "operational_status", "OPEN", "tour_title", "Tour SGU",
                "meeting_points", List.of(Map.of("confirmationKey", key, "address", "Điểm A", "arrivalTime", "05:00"),
                        Map.of("confirmationKey", "b".repeat(64), "address", "Điểm B", "arrivalTime", "05:30"))));
        var profile = Map.<String, Object>of("id", 7L, "email", "tester@example.com");
        when(auth.getUsersBatch(List.of(7L))).thenReturn(List.of(profile));
        when(auth.getUserById(7L)).thenReturn(profile);
        var publisher = new BookingMailOutboxPublisher(db.jdbc, mock(NotificationMailClient.class), "1970-01-01T00:00:00");
        var pickups = new BookingPickupService(db.jdbc, access, tours, auth, publisher,
                new ObjectMapper(), new MailProperties(), clock, db.manager, mock(DepartureManifestService.class),
                mock(com.example.booking_service.operations.AdminAuditEvents.class));

        assertEquals(1, pickups.announce(1).queued());
        assertEquals(1, pickups.announce(1).alreadyQueued());
        when(access.accountId()).thenReturn(8);
        assertThrows(ResponseStatusException.class, () -> pickups.select(10, key));
        when(access.accountId()).thenReturn(7);
        assertThrows(ResponseStatusException.class, () -> pickups.select(10, "c".repeat(64)));
        assertEquals(2, pickups.options(10).options().size());
        assertEquals(key, pickups.select(10, key).selectedKey());
        assertEquals("Điểm A", db.jdbc.queryForObject(
                "SELECT address FROM booking_pickup_snapshot WHERE booking_id=10", String.class));
        assertEquals(key, pickups.select(10, key).selectedKey());
        assertEquals(2, db.count("booking_outbox"));
        clock.set(LocalDateTime.of(2026, 11, 20, 4, 30));
        assertFalse(pickups.options(10).editable());
        assertThrows(ResponseStatusException.class, () -> pickups.select(10, "b".repeat(64)));
    }
}
