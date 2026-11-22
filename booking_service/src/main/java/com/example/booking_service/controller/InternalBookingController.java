package com.example.booking_service.controller;

import com.example.booking_service.repository.httpclient.AuthInternalClient;
import com.example.booking_service.repository.httpclient.TourInternalClient;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/internal/booking")
@RequiredArgsConstructor
public class InternalBookingController {

	private final JdbcTemplate jdbc;
	private final TourInternalClient tourClient;
	private final AuthInternalClient authClient;

	@GetMapping("/users/{userId}/active-bookings")
	public ResponseEntity<List<Map<String, Object>>> getActiveBookings(@PathVariable Long userId) {
		LocalDateTime current = LocalDateTime.now();
		var bookings = jdbc.queryForList("""
			SELECT b.id, b.status, b.tour_departure_id
			FROM booking b
			WHERE b.account_id = ? AND b.status IN ('PENDING_PAYMENT','CONFIRMED')
			""", userId);
		
		// Enrich with departure info via Tour internal API
		for (var booking : bookings) {
			Integer departureId = (Integer) booking.get("tour_departure_id");
			if (departureId != null) {
				try {
					Map<String, Object> departure = tourClient.getDeparture(departureId);
					if (departure != null) {
						booking.put("departure_date", departure.get("departure_date"));
						booking.put("return_date", departure.get("return_date"));
						booking.put("operational_status", departure.get("operational_status"));
					}
				} catch (Exception ignored) {
					// Tour service unavailable, continue without tour info
				}
			}
		}
		
		return ResponseEntity.ok(bookings);
	}

	@GetMapping("/users/{userId}/pending-changes")
	public ResponseEntity<List<Map<String, Object>>> getPendingChanges(@PathVariable Long userId) {
		var changes = jdbc.queryForList("""
			SELECT id, booking_id, status, kind, active_key
			FROM booking_change
			WHERE account_id = ? AND active_key = 1
			""", userId);
		return ResponseEntity.ok(changes);
	}

	@GetMapping("/bookings/{id}")
	public ResponseEntity<Map<String, Object>> getBooking(@PathVariable Integer id) {
		var booking = jdbc.queryForMap("SELECT * FROM booking WHERE id = ?", id);
		if (booking == null || booking.isEmpty()) {
			return ResponseEntity.notFound().build();
		}
		
		// Get user info via Auth internal API
		Integer accountId = (Integer) booking.get("account_id");
		if (accountId != null) {
			try {
				Map<String, Object> user = authClient.getUserById(accountId.longValue());
				if (user != null) {
					booking.put("username", user.get("username"));
					booking.put("email", user.get("email"));
				}
			} catch (Exception ignored) {
				// Auth service unavailable, continue without user info
			}
		}
		
		// Get tour info via Tour internal API
		Integer departureId = (Integer) booking.get("tour_departure_id");
		if (departureId != null) {
			try {
				Map<String, Object> departure = tourClient.getDeparture(departureId);
				if (departure != null) {
					booking.put("departure_date", departure.get("departure_date"));
					booking.put("return_date", departure.get("return_date"));
					booking.put("operational_status", departure.get("operational_status"));
					booking.put("tour_title", departure.get("tour_id") != null ? "Tour #" + departure.get("tour_id") : "Tour");
				}
			} catch (Exception ignored) {
				// Tour service unavailable, continue without tour info
			}
		}
		
		return ResponseEntity.ok(booking);
	}

	@GetMapping("/bookings/{id}/owner")
	public ResponseEntity<Map<String, Object>> getBookingOwner(@PathVariable Integer id) {
		var owner = jdbc.queryForMap("SELECT account_id FROM booking WHERE id = ?", id);
		if (owner == null || owner.isEmpty()) {
			return ResponseEntity.notFound().build();
		}
		return ResponseEntity.ok(owner);
	}

	@GetMapping("/bookings/{id}/reservation")
	public ResponseEntity<Map<String, Object>> getBookingReservation(@PathVariable Integer id) {
		var reservation = jdbc.queryForMap("SELECT * FROM booking_reservation WHERE booking_id = ?", id);
		if (reservation == null || reservation.isEmpty()) {
			return ResponseEntity.notFound().build();
		}
		return ResponseEntity.ok(reservation);
	}

	@GetMapping("/bookings/{id}/customers")
	public ResponseEntity<List<Map<String, Object>>> getBookingCustomers(@PathVariable Integer id) {
		var customers = jdbc.queryForList("SELECT booking_type FROM customer WHERE booking_id = ? ORDER BY id", id);
		return ResponseEntity.ok(customers);
	}

	@GetMapping("/bookings/{id}/price-snapshot")
	public ResponseEntity<Map<String, Object>> getBookingPriceSnapshot(@PathVariable Integer id) {
		var snapshot = jdbc.queryForMap("SELECT total_amount FROM booking_price_snapshot WHERE booking_id = ?", id);
		if (snapshot == null || snapshot.isEmpty()) {
			return ResponseEntity.notFound().build();
		}
		return ResponseEntity.ok(snapshot);
	}

	@GetMapping("/bookings/{id}/payment-evidence")
	public ResponseEntity<Map<String, Object>> getPaymentEvidence(@PathVariable Integer id) {
		LocalDateTime now = LocalDateTime.now();
		var evidence = jdbc.queryForMap("""
			SELECT (
				SELECT COUNT(*) FROM invoice WHERE booking_id = ? AND status = 'PAID'
			) + (
				SELECT COUNT(*) FROM payment WHERE booking_id = ? AND status = 'PAID'
			) + (
				SELECT COUNT(*) FROM vnpay_transaction
				WHERE booking_id = ? AND (status = 'PAID' OR (status = 'PENDING' AND expires_at > ?))
			) + (
				SELECT COUNT(*) FROM momo_transaction
				WHERE booking_id = ? AND (status = 'PAID' OR (status = 'PENDING' AND expires_at > ?))
			) + (
				SELECT COUNT(*) FROM payment_reconciliation WHERE booking_id = ? AND status = 'OPEN'
			) AS evidence_count
			""", id, id, id, now, id, now, id);
		if (evidence == null || evidence.isEmpty()) {
			return ResponseEntity.notFound().build();
		}
		return ResponseEntity.ok(evidence);
	}

	@PostMapping("/bookings/{id}/decrement-seats")
	public ResponseEntity<Void> decrementSeats(@PathVariable Integer id, @RequestBody DecrementSeatsRequest request) {
		var booking = jdbc.queryForMap("SELECT tour_departure_id FROM booking WHERE id = ?", id);
		if (booking == null || booking.isEmpty()) {
			return ResponseEntity.notFound().build();
		}
		int departureId = ((Number) booking.get("tour_departure_id")).intValue();
		int updated = jdbc.update("""
			UPDATE tour_departure SET available_seats = available_seats - ?, version = version + 1
			WHERE id = ? AND available_seats >= ?
			""", request.seats(), departureId, request.seats());
		if (updated != 1) {
			return ResponseEntity.status(409).build();
		}
		return ResponseEntity.ok().build();
	}

	public record DecrementSeatsRequest(Integer seats) {}
}