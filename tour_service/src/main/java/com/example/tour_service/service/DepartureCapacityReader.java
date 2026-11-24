package com.example.tour_service.service;

import com.example.tour_service.entity.TourDeparture;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Read projection backed exclusively by Tour-owned tables (tour_departure + departure_hold).
 * Caller locks departure before reading. Never repairs data.
 */
@Component
public class DepartureCapacityReader {
    /** The one issue a confirmed capacity repair is allowed to resolve. */
    public static final String CAPACITY_MISMATCH =
            "Tổng sức chứa không khớp chỗ đã chiếm và chỗ còn lại; cần đối chiếu dữ liệu";

    private final JdbcTemplate jdbc;

    public DepartureCapacityReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record State(int paidSeats, int heldSeats, int overdueHolds, int historyCount,
                        boolean relatedHistory, List<String> issues, List<String> warnings) {
        public int occupied() {
            return Math.addExact(paidSeats, heldSeats);
        }
    }

    public State read(TourDeparture departure, LocalDateTime now) {
        var rows = jdbc.queryForList("""
                SELECT booking_id, seats, status, expires_at
                FROM departure_hold
                WHERE departure_id = ?
                ORDER BY created_at, hold_id
                """, departure.getId());

        int paid = 0;
        int held = 0;
        int overdue = 0;
        var issues = new LinkedHashSet<String>();
        var warnings = new LinkedHashSet<String>();

        for (var row : rows) {
            int seats = number(row, "seats");
            int bookingId = number(row, "booking_id");
            String status = String.valueOf(row.get("status"));
            if (seats <= 0) {
                issues.add("Hold cho booking #" + bookingId + " có số chỗ không hợp lệ");
                continue;
            }
            if ("CONFIRMED".equals(status) || "PAID".equals(status)) {
                paid = Math.addExact(paid, seats);
            } else if ("HELD".equals(status) || "CHECKOUT".equals(status)) {
                held = Math.addExact(held, seats);
                LocalDateTime expiresAt = date(row.get("expires_at"));
                if (expiresAt != null && !expiresAt.isAfter(now)) {
                    overdue++;
                }
            } else if (!List.of("RELEASED", "EXPIRED", "CANCELLED").contains(status)) {
                issues.add("Hold cho booking #" + bookingId + " có trạng thái cần đối chiếu: " + status);
            }
        }

        if (overdue > 0 && "OPEN".equals(departure.getOperationalStatus())) {
            issues.add("Còn giữ chỗ quá hạn chưa xử lý; cần chạy Booking service rồi tải lại");
        }
        if (departure.getAvailableSeats() == null || departure.getAvailableSeats() < 0) {
            issues.add("Số chỗ còn lại không hợp lệ");
        }
        if (departure.getCapacity() != null && (departure.getCapacity() < 0 || departure.getCapacity() > 32767
                || departure.getAvailableSeats() == null
                || departure.getCapacity() != paid + held + departure.getAvailableSeats())) {
            issues.add(CAPACITY_MISMATCH);
        }

        boolean history = !rows.isEmpty();
        return new State(paid, held, overdue, rows.size(), history, List.copyOf(issues), List.copyOf(warnings));
    }

    private static int number(Map<String, Object> row, String name) {
        Object val = row.get(name);
        return val instanceof Number n ? n.intValue() : 0;
    }

    private static LocalDateTime date(Object value) {
        if (value == null) return null;
        return value instanceof LocalDateTime time ? time : ((java.sql.Timestamp) value).toLocalDateTime();
    }
}
