package com.example.booking_service.reservation;

import com.example.booking_service.repository.httpclient.InvoiceInternalClient;
import com.example.booking_service.repository.httpclient.TourInternalClient;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class BookingTicketMailContext {
    private final JdbcTemplate jdbc;
    private final TourInternalClient tours;
    private final InvoiceInternalClient invoices;

    public record Pickup(String address, String arrivalTime) {}
    public record Ticket(String title, String tourCode, String departure, String returns, String cover,
                         int passengers, BigDecimal amountVnd, Pickup pickup) {}

    public Ticket forBooking(int bookingId) {
        var rows = jdbc.queryForList("""
                SELECT b.tour_departure_id, p.total_amount,
                    (SELECT COUNT(*) FROM customer c WHERE c.booking_id=b.id) AS passengers
                FROM booking b LEFT JOIN booking_price_snapshot p ON p.booking_id=b.id
                WHERE b.id=?
                """, bookingId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy vé");
        var row = rows.getFirst();
        int departureId = ((Number) row.get("tour_departure_id")).intValue();
        var departure = tours.getDeparture(departureId);
        int tourId = ((Number) departure.get("tour_id")).intValue();
        var point = jdbc.query("""
                SELECT address,arrival_time FROM booking_pickup_snapshot WHERE booking_id=? AND departure_id=?
                """, (rs, i) -> new Pickup(rs.getString("address"), rs.getString("arrival_time")), bookingId, departureId);
        return new Ticket(Objects.toString(departure.get("tour_title"), "Tour SGU"),
                Objects.toString(departure.get("tour_code"), ""),
                Objects.toString(BookingMailEvents.date(departure.get("departure_date")), ""),
                Objects.toString(BookingMailEvents.date(departure.get("return_date")), ""),
                cover(tourId), ((Number) row.get("passengers")).intValue(), amount(bookingId, row),
                point.isEmpty() ? null : point.getFirst());
    }

    private String cover(int tourId) {
        try {
            var envelope = tours.getTourForMail(tourId);
            if (envelope != null && envelope.get("result") instanceof Map<?, ?> tour
                    && tour.get("imageIds") instanceof List<?> images) {
                return images.stream().filter(String.class::isInstance).map(String.class::cast)
                        .filter(url -> url.startsWith("https://res.cloudinary.com/")).findFirst().orElse(null);
            }
        } catch (FeignException unavailable) {
            // The ticket can still be sent if the optional cover cannot be loaded.
        }
        return null;
    }

    private BigDecimal amount(int bookingId, Map<String, Object> row) {
        try {
            var invoice = invoices.getInvoiceByBookingId(bookingId);
            if (invoice != null && invoice.get("totalBookingTourExpense") != null)
                return new BigDecimal(invoice.get("totalBookingTourExpense").toString());
        } catch (FeignException unavailable) {
            // The immutable booking price remains available before Invoice catches up.
        }
        return row.get("total_amount") == null ? null : new BigDecimal(row.get("total_amount").toString());
    }
}
