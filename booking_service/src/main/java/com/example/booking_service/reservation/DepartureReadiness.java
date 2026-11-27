package com.example.booking_service.reservation;

import com.example.booking_service.repository.httpclient.TourInternalClient;
import feign.FeignException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

@Service
public class DepartureReadiness {
    public enum Issue { OVERDUE_UNCONFIRMED, MISSING_GUIDE, MISSING_VEHICLE, OVER_CAPACITY, MISSING_PICKUP, NEEDS_RECONFIRMATION }
    public record Alert(int departureId, int tourId, String code, String title, LocalDateTime departure,
                        Issue issue, Integer bookingId, Integer passengers, Integer vehicleCapacity) {}
    public record Page(List<Alert> items, int total, boolean hasNext) {}

    private final JdbcTemplate jdbc;
    private final BookingAccess access;
    private final TourInternalClient tours;
    private final DepartureManifestService manifests;
    private final Clock clock;

    public DepartureReadiness(JdbcTemplate jdbc, BookingAccess access, TourInternalClient tours,
            DepartureManifestService manifests, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.tours = tours;
        this.manifests = manifests;
        this.clock = clock;
    }

    public Page alerts(int page, int size, Integer bookingId, LocalDate day) {
        if (!access.admin())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chỉ quản trị viên được xem cảnh báo vận hành.");
        if (page < 0 || size < 1 || size > 100 || bookingId != null && bookingId < 1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bộ lọc cảnh báo không hợp lệ.");
        try {
            return scan(page, size, bookingId, day);
        } catch (FeignException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Chưa đối chiếu được lịch vận hành.", unavailable);
        }
    }

    private Page scan(int page, int size, Integer bookingId, LocalDate day) {
        var now = LocalDateTime.now(clock);
        var departures = tours.reportDepartures();
        var started = new java.util.HashSet<>(jdbc.queryForList("""
                SELECT departure_id FROM departure_manifest
                WHERE actual_departure_at IS NOT NULL
                """, Integer.class));
        var closed = new java.util.HashSet<>(jdbc.queryForList("""
                SELECT departure_id FROM departure_manifest WHERE completed_at IS NOT NULL
                """, Integer.class));
        var guides = new java.util.HashSet<>(jdbc.queryForList("SELECT departure_id FROM departure_guide_assignment", Integer.class));
        var orders = new HashMap<Integer, List<Integer>>();
        jdbc.query("""
                SELECT tour_departure_id,id FROM booking WHERE status='CONFIRMED' ORDER BY id
                """, rs -> {
            orders.computeIfAbsent(rs.getInt(1), ignored -> new ArrayList<>()).add(rs.getInt(2));
        });
        var alerts = new ArrayList<Alert>();
        for (var row : departures) {
            if (!"OPEN".equals(row.get("operationalStatus"))) continue;
            var departure = BookingMailEvents.date(row.get("departure"));
            int id = ((Number) row.get("id")).intValue();
            if (departure == null || closed.contains(id) || departure.isAfter(now.plusDays(7))
                    || day != null && !day.equals(departure.toLocalDate())) continue;
            var candidates = orders.getOrDefault(id, List.of());
            if (bookingId != null && !candidates.contains(bookingId)) continue;
            var paid = candidates.isEmpty() ? java.util.Set.<Integer>of() : manifests.paidBookings(id);
            boolean overdue = !now.isBefore(departure);
            boolean alreadyDeparted = started.contains(id);
            if (alreadyDeparted && paid.isEmpty()) continue;
            if (bookingId == null && !alreadyDeparted) {
                if (overdue) alerts.add(alert(row, departure, Issue.OVERDUE_UNCONFIRMED, null, null, null));
                if (!guides.contains(id)) alerts.add(alert(row, departure, Issue.MISSING_GUIDE, null, null, null));
                int capacity = ((Number) row.getOrDefault("vehicleCapacity", 0)).intValue();
                if (capacity <= 0) alerts.add(alert(row, departure, Issue.MISSING_VEHICLE, null, null, null));
                else if (!paid.isEmpty()) {
                    int people = jdbc.query("""
                            SELECT c.booking_id FROM customer c JOIN booking b ON b.id=c.booking_id
                            WHERE b.tour_departure_id=? AND b.status='CONFIRMED'
                            """, (rs, index) -> rs.getInt(1), id).stream().filter(paid::contains).toList().size();
                    if (people > capacity) alerts.add(alert(row, departure, Issue.OVER_CAPACITY, null, people, capacity));
                }
            }
            if (!paid.isEmpty()) {
                var options = BookingPickupService.parseOptions(tours.getDeparture(id));
                for (int paidId : candidates) {
                    if (!paid.contains(paidId) || bookingId != null && bookingId != paidId) continue;
                    var selection = BookingPickupService.selection(jdbc, paidId, id, options);
                    switch (selection.status()) {
                        case MISSING -> {
                            if (!alreadyDeparted) alerts.add(alert(row, departure, Issue.MISSING_PICKUP, paidId, null, null));
                        }
                        case NEEDS_RECONFIRMATION -> alerts.add(alert(row, departure, Issue.NEEDS_RECONFIRMATION, paidId, null, null));
                        case CONFIRMED -> { }
                    }
                }
            }
        }
        alerts.sort(Comparator.comparing((Alert alert) -> alert.departure().isBefore(now.minusDays(7)))
                .thenComparingLong(alert -> Math.abs(java.time.Duration.between(now, alert.departure()).toSeconds()))
                .thenComparing(Alert::departureId)
                .thenComparing(Alert::issue).thenComparing(Alert::bookingId, Comparator.nullsFirst(Comparator.naturalOrder())));
        int start = (int) Math.min((long) page * size, alerts.size());
        int end = Math.min(start + size, alerts.size());
        return new Page(List.copyOf(alerts.subList(start, end)), alerts.size(), end < alerts.size());
    }

    private static Alert alert(java.util.Map<String, Object> row, LocalDateTime departure, Issue issue, Integer booking,
            Integer people, Integer capacity) {
        return new Alert(((Number) row.get("id")).intValue(), ((Number) row.get("tourId")).intValue(),
                (String) row.get("code"), (String) row.get("title"), departure, issue, booking, people, capacity);
    }
}
