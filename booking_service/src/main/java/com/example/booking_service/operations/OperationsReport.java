package com.example.booking_service.operations;

import com.example.booking_service.change.ChangeLedger;
import com.example.booking_service.repository.httpclient.PaymentInternalClient;
import com.example.booking_service.repository.httpclient.TourInternalClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

record OperationsReport(JdbcTemplate jdbc, TourInternalClient tours, PaymentInternalClient payments, Clock clock) {
    private static final List<String> METRICS = List.of("bookings", "expired", "completed", "collected",
            "schedule_refunds", "reconciliation_refunds", "confirmed_passengers");
    private record Departure(int tourId, LocalDateTime departure, LocalDateTime returns, String region) {}

    Map<String, Object> read(LocalDate from, LocalDate through, Integer tourId, String region) {
        if (from == null || through == null || through.isBefore(from) || through.isAfter(from.plusYears(1).minusDays(1))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Khoảng báo cáo tối đa một năm");
        }
        if ((tourId != null && tourId < 1) || (region != null && !Set.of("NORTH", "CENTRAL", "SOUTH", "UNKNOWN").contains(region))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bộ lọc tour hoặc miền không hợp lệ");
        }
        var departures = new HashMap<Integer, Departure>();
        var catalogue = new TreeMap<Integer, Map<String, Object>>();
        List<Map<String, Object>> financial;
        try {
            for (var row : tours.reportDepartures()) {
                int id = number(row, "tourId").intValue();
                String area = row.get("region").toString();
                departures.put(number(row, "id").intValue(), new Departure(id,
                        ChangeLedger.date(row.get("departure")), ChangeLedger.date(row.get("returns")), area));
                catalogue.putIfAbsent(id, Map.of("id", id, "title", row.get("title"), "region", area));
            }
            financial = payments.reportAmounts(from.toString(), through.toString());
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Chưa tải đủ dữ liệu tour hoặc thanh toán", e);
        }

        var start = from.atStartOfDay();
        var until = through.plusDays(1).atStartOfDay();
        boolean monthly = through.isAfter(from.plusDays(44));
        var totals = new LinkedHashMap<String, Long>();
        METRICS.forEach(key -> totals.put(key, 0L));
        var series = new TreeMap<String, Map<String, Long>>();
        for (var day = from; !day.isAfter(through); day = day.plusDays(1)) {
            series.computeIfAbsent(bucket(day, monthly), ignored -> new LinkedHashMap<>(totals));
        }

        var financialByBooking = new HashMap<Integer, List<Map<String, Object>>>();
        for (var row : financial) {
            financialByBooking.computeIfAbsent(number(row, "booking_id").intValue(), ignored -> new ArrayList<>()).add(row);
        }
        var scheduleRefunds = new HashMap<Integer, List<Map<String, Object>>>();
        for (var row : jdbc.queryForList("""
                SELECT booking_id, updated_at, refund_amount
                FROM booking_change WHERE status='REFUNDED' AND updated_at>=? AND updated_at<?
                """, start, until)) {
            scheduleRefunds.computeIfAbsent(number(row, "booking_id").intValue(), ignored -> new ArrayList<>()).add(row);
        }

        var legacyCompleted = new LinkedHashMap<Integer, LocalDate>();
        var currentTime = LocalDateTime.now(clock);
        jdbc.query("""
                SELECT b.id, b.status, b.created_at, b.tour_departure_id, r.status AS reservation_status,
                       (SELECT COUNT(*) FROM customer c WHERE c.booking_id=b.id) AS seats
                FROM booking b LEFT JOIN booking_reservation r ON r.booking_id=b.id
                WHERE b.created_at < ? OR b.created_at IS NULL
                """, rs -> {
            int booking = rs.getInt("id");
            var bookingFinancial = financialByBooking.remove(booking);
            var departure = departures.get(rs.getInt("tour_departure_id"));
            if (tourId != null && (departure == null || departure.tourId() != tourId)) return;
            if (region != null && !region.equals(departure == null ? "UNKNOWN" : departure.region())) return;
            var created = rs.getObject("created_at", LocalDateTime.class);
            String status = rs.getString("status");
            if (inside(created, start, until)) {
                add(totals, series, "bookings", created.toLocalDate(), 1, monthly);
                if ("EXPIRED".equals(status)) add(totals, series, "expired", created.toLocalDate(), 1, monthly);
            }
            if ("CONFIRMED".equals(status) && departure != null) {
                if (inside(departure.departure(), start, until)) {
                    add(totals, series, "confirmed_passengers", departure.departure().toLocalDate(), rs.getLong("seats"), monthly);
                }
                if (inside(departure.returns(), start, until) && !departure.returns().isAfter(currentTime)) {
                    String reservation = rs.getString("reservation_status");
                    if ("PAID".equals(reservation)) {
                        add(totals, series, "completed", departure.returns().toLocalDate(), 1, monthly);
                    } else if (reservation == null) {
                        legacyCompleted.put(booking, departure.returns().toLocalDate());
                    }
                }
            }
            for (var row : bookingFinancial != null ? bookingFinancial : List.<Map<String, Object>>of()) {
                add(totals, series, row.get("metric").toString(), LocalDate.parse(row.get("day").toString()), number(row, "amount").longValue(), monthly);
            }
            for (var row : scheduleRefunds.getOrDefault(booking, List.of())) {
                var at = ChangeLedger.date(row.get("updated_at"));
                add(totals, series, "schedule_refunds", at.toLocalDate(), number(row, "refund_amount").longValue(), monthly);
            }
        }, until);

        if (!legacyCompleted.isEmpty()) {
            var ids = new ArrayList<>(legacyCompleted.keySet());
            try {
                for (int i = 0; i < ids.size(); i += 100) {
                    for (var row : payments.settlements(ids.subList(i, Math.min(i + 100, ids.size())))) {
                        var day = legacyCompleted.get(number(row, "bookingId").intValue());
                        if (day != null && "PAID".equals(row.get("status"))) {
                            legacyCompleted.remove(number(row, "bookingId").intValue());
                            add(totals, series, "completed", day, 1, monthly);
                        }
                    }
                }
            } catch (RuntimeException e) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Chưa xác minh được thanh toán của các đơn cũ", e);
            }
        }

        if (tourId == null && (region == null || "UNKNOWN".equals(region))) {
            for (var rows : financialByBooking.values()) {
                for (var row : rows) {
                    add(totals, series, row.get("metric").toString(), LocalDate.parse(row.get("day").toString()), number(row, "amount").longValue(), monthly);
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>(totals);
        result.put("series", series.entrySet().stream().map(entry -> {
            Map<String, Object> point = new LinkedHashMap<>(entry.getValue());
            point.put("period", entry.getKey());
            return point;
        }).toList());
        result.put("granularity", monthly ? "MONTH" : "DAY");
        result.put("tours", catalogue.values());
        return result;
    }

    private static String bucket(LocalDate day, boolean monthly) {
        return monthly ? YearMonth.from(day).toString() : day.toString();
    }

    private static boolean inside(LocalDateTime value, LocalDateTime from, LocalDateTime until) {
        return value != null && !value.isBefore(from) && value.isBefore(until);
    }

    private static Number number(Map<String, Object> row, String key) {
        return row.get(key) instanceof Number value ? value : 0;
    }

    private static void add(Map<String, Long> totals, Map<String, Map<String, Long>> series,
                            String metric, LocalDate day, long value, boolean monthly) {
        totals.merge(metric, value, Long::sum);
        series.get(bucket(day, monthly)).merge(metric, value, Long::sum);
    }
}
