package com.example.booking_service.saga;

import com.example.booking_service.repository.httpclient.AuthInternalClient;
import com.example.booking_service.repository.httpclient.NotificationInternalClient;
import com.example.booking_service.repository.httpclient.TourInternalClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingHoldSaga {

	private final JdbcTemplate jdbc;
	private final TourInternalClient tourClient;
	private final NotificationInternalClient notificationClient;
	private final AuthInternalClient authClient;

	public enum SagaState {
		HOLD_CREATED,
		PAYMENT_PENDING,
		PAYMENT_CONFIRMED,
		HOLD_EXPIRED,
		HOLD_CANCELLED,
		COMPENSATING,
		COMPENSATED,
		FAILED
	}

	public enum SagaEvent {
		HOLD_CREATED,
		PAYMENT_INITIATED,
		PAYMENT_SUCCESS,
		PAYMENT_FAILED,
		HOLD_EXPIRED,
		HOLD_CANCELLED,
		COMPENSATE_RELEASE_SEATS,
		COMPENSATE_NOTIFY
	}

	@Transactional
	public void handleHoldCreated(int bookingId, int departureId, int seats, LocalDateTime expiresAt) {
		String sagaId = java.util.UUID.randomUUID().toString();
		
		jdbc.update("""
			INSERT INTO booking_saga (id, saga_type, aggregate_id, status, current_step, payload, attempts, created_at, updated_at)
			VALUES (?, 'HOLD_EXPIRY', ?, 'HOLD_CREATED', 'HOLD_CREATED', ?, 0, ?, ?)
			""", sagaId, String.valueOf(bookingId), 
			toJson(Map.of(
				"bookingId", bookingId,
				"departureId", departureId,
				"seats", seats,
				"expiresAt", expiresAt.toString()
			)), LocalDateTime.now(), LocalDateTime.now());

		log.info("Started hold expiry saga {} for booking {}", sagaId, bookingId);
	}

	@Transactional
	public void handlePaymentInitiated(int bookingId) {
		var saga = jdbc.queryForList("SELECT * FROM booking_saga WHERE aggregate_id = ? AND saga_type = 'HOLD_EXPIRY' AND status IN ('HOLD_CREATED', 'PAYMENT_PENDING') ORDER BY created_at DESC LIMIT 1", String.valueOf(bookingId));
		if (saga.isEmpty()) return;

		String sagaId = (String) saga.getFirst().get("id");
		jdbc.update("UPDATE booking_saga SET status = 'PAYMENT_PENDING', current_step = 'PAYMENT_INITIATED', updated_at = ? WHERE id = ?", LocalDateTime.now(), sagaId);
		log.debug("Saga {} moved to PAYMENT_PENDING for booking {}", sagaId, bookingId);
	}

	@Transactional
	public void handlePaymentSuccess(int bookingId) {
		var saga = jdbc.queryForList("SELECT * FROM booking_saga WHERE aggregate_id = ? AND saga_type = 'HOLD_EXPIRY' AND status IN ('HOLD_CREATED', 'PAYMENT_PENDING') ORDER BY created_at DESC LIMIT 1", String.valueOf(bookingId));
		if (saga.isEmpty()) return;

		String sagaId = (String) saga.getFirst().get("id");
		jdbc.update("UPDATE booking_saga SET status = 'PAYMENT_CONFIRMED', current_step = 'PAYMENT_SUCCESS', updated_at = ? WHERE id = ?", LocalDateTime.now(), sagaId);
		log.info("Saga {} completed successfully for booking {}", sagaId, bookingId);
	}

	@Transactional
	public void handlePaymentFailed(int bookingId) {
		var saga = jdbc.queryForList("SELECT * FROM booking_saga WHERE aggregate_id = ? AND saga_type = 'HOLD_EXPIRY' AND status IN ('HOLD_CREATED', 'PAYMENT_PENDING') ORDER BY created_at DESC LIMIT 1", String.valueOf(bookingId));
		if (saga.isEmpty()) return;

		String sagaId = (String) saga.getFirst().get("id");
		jdbc.update("UPDATE booking_saga SET status = 'COMPENSATING', current_step = 'COMPENSATE_RELEASE_SEATS', updated_at = ? WHERE id = ?", LocalDateTime.now(), sagaId);
		
		// Trigger compensation
		compensateHoldExpiry(sagaId);
	}

	@Transactional
	public void handleHoldExpired(int bookingId) {
		var saga = jdbc.queryForList("SELECT * FROM booking_saga WHERE aggregate_id = ? AND saga_type = 'HOLD_EXPIRY' AND status IN ('HOLD_CREATED', 'PAYMENT_PENDING') ORDER BY created_at DESC LIMIT 1", String.valueOf(bookingId));
		if (saga.isEmpty()) return;

		String sagaId = (String) saga.getFirst().get("id");
		Map<String, Object> payload = parseJson((String) saga.getFirst().get("payload"));
		
		int departureId = ((Number) payload.get("departureId")).intValue();
		int seats = ((Number) payload.get("seats")).intValue();

		jdbc.update("UPDATE booking_saga SET status = 'HOLD_EXPIRED', current_step = 'HOLD_EXPIRED', updated_at = ? WHERE id = ?", LocalDateTime.now(), sagaId);
		
		// Release seats
		releaseSeats(departureId, seats);
		
		// Send expiry notification
		sendMail(bookingId, "BOOKING_EXPIRED");
		
		log.info("Hold expired for booking {}, saga {}", bookingId, sagaId);
	}

	@Transactional
	public void handleHoldCancelled(int bookingId, String reason) {
		var saga = jdbc.queryForList("SELECT * FROM booking_saga WHERE aggregate_id = ? AND saga_type = 'HOLD_EXPIRY' AND status IN ('HOLD_CREATED', 'PAYMENT_PENDING') ORDER BY created_at DESC LIMIT 1", String.valueOf(bookingId));
		if (saga.isEmpty()) return;

		String sagaId = (String) saga.getFirst().get("id");
		Map<String, Object> payload = parseJson((String) saga.getFirst().get("payload"));
		
		int departureId = ((Number) payload.get("departureId")).intValue();
		int seats = ((Number) payload.get("seats")).intValue();

		jdbc.update("UPDATE booking_saga SET status = 'HOLD_CANCELLED', current_step = 'HOLD_CANCELLED', updated_at = ? WHERE id = ?", LocalDateTime.now(), sagaId);
		
		// Release seats
		releaseSeats(departureId, seats);
		
		log.info("Hold cancelled for booking {}, saga {}", bookingId, sagaId);
	}

	private void compensateHoldExpiry(String sagaId) {
		var saga = jdbc.queryForMap("SELECT * FROM booking_saga WHERE id = ?", sagaId);
		if (saga == null) return;

		Map<String, Object> payload = parseJson((String) saga.get("payload"));
		int bookingId = ((Number) payload.get("bookingId")).intValue();
		int departureId = ((Number) payload.get("departureId")).intValue();
		int seats = ((Number) payload.get("seats")).intValue();

		try {
			// Release seats
			releaseSeats(departureId, seats);
			
			// Update booking status
			jdbc.update("UPDATE booking SET status = 'EXPIRED' WHERE id = ? AND status IN ('PENDING_PAYMENT', 'CONFIRMED')", bookingId);
			
			// Update reservation
			jdbc.update("UPDATE booking_reservation SET status = 'EXPIRED', cancel_reason = 'PAYMENT_FAILED', updated_at = ? WHERE booking_id = ?", LocalDateTime.now(), bookingId);
			
			// Send notification
			sendMail(bookingId, "BOOKING_EXPIRED");
			
			jdbc.update("UPDATE booking_saga SET status = 'COMPENSATED', current_step = 'COMPENSATED', updated_at = ? WHERE id = ?", LocalDateTime.now(), sagaId);
			log.info("Compensation completed for saga {}", sagaId);
		} catch (Exception e) {
			log.error("Compensation failed for saga {}: {}", sagaId, e.getMessage(), e);
			jdbc.update("UPDATE booking_saga SET status = 'FAILED', current_step = 'COMPENSATION_FAILED', last_error = ?, updated_at = ? WHERE id = ?", e.getMessage(), LocalDateTime.now(), sagaId);
		}
	}

	private void releaseSeats(int departureId, int seats) {
		try {
			tourClient.releaseHold(departureId, seats);
		} catch (Exception e) {
			log.warn("Failed to release hold via Tour internal API for departure {}: {}", departureId, e.getMessage());
			// Fallback to direct update
			jdbc.update("UPDATE tour_departure SET available_seats = LEAST(COALESCE(capacity, 32767), available_seats + ?), version = version + 1 WHERE id = ?", seats, departureId);
		}
	}

	@Scheduled(fixedDelay = 60000) // Check every minute
	@Transactional
	public void processExpiredHolds() {
		LocalDateTime now = LocalDateTime.now();
		
		var expiredSagas = jdbc.queryForList("""
			SELECT s.* FROM booking_saga s
			JOIN booking_reservation r ON r.booking_id = s.aggregate_id
			WHERE s.saga_type = 'HOLD_EXPIRY' 
			AND s.status IN ('HOLD_CREATED', 'PAYMENT_PENDING')
			AND r.status IN ('HELD', 'CHECKOUT')
			AND r.expires_at <= ?
			""", now);

		for (var saga : expiredSagas) {
			String sagaId = (String) saga.get("id");
			String aggregateId = (String) saga.get("aggregate_id");
			int bookingId = Integer.parseInt(aggregateId);
			
			try {
				handleHoldExpired(bookingId);
			} catch (Exception e) {
				log.error("Failed to process expired hold for saga {}: {}", sagaId, e.getMessage(), e);
				jdbc.update("UPDATE booking_saga SET status = 'FAILED', last_error = ?, updated_at = ? WHERE id = ?", e.getMessage(), LocalDateTime.now(), sagaId);
			}
		}
	}

	private void sendMail(int bookingId, String eventType) {
		try {
			// Get booking basic info
			var booking = jdbc.queryForMap("SELECT account_id, tour_departure_id FROM booking WHERE id=?", bookingId);
			if (booking == null || booking.isEmpty()) {
				log.warn("Booking {} not found for mail event {}", bookingId, eventType);
				return;
			}
			
			int accountId = ((Number) booking.get("account_id")).intValue();
			int departureId = ((Number) booking.get("tour_departure_id")).intValue();
			
			// Get user info via Auth internal API
			String email, username;
			try {
				Map<String, Object> user = authClient.getUserById((long) accountId);
				email = (String) user.get("email");
				username = (String) user.get("username");
			} catch (Exception e) {
				log.warn("Cannot fetch user info for booking {}", bookingId);
				return;
			}
			
			// Get tour info via Tour internal API
			String tourTitle;
			LocalDateTime departureDate;
			try {
				Map<String, Object> departure = tourClient.getDeparture(departureId);
				tourTitle = (String) departure.get("tour_id") != null ? "Tour #" + departure.get("tour_id") : "Tour";
				Object depDate = departure.get("departure_date");
				departureDate = depDate instanceof LocalDateTime ? (LocalDateTime) depDate : ((java.sql.Timestamp) depDate).toLocalDateTime();
			} catch (Exception e) {
				log.warn("Cannot fetch tour info for booking {}", bookingId);
				return;
			}
			
			var request = new NotificationInternalClient.MailEnqueueRequest(
					"booking:" + bookingId + ":" + eventType.toLowerCase(),
					eventType,
					"booking-service",
					bookingId,
					accountId,
					email,
					buildSubject(eventType, tourTitle, departureDate),
					buildHtml(eventType, tourTitle, departureDate, username),
					buildText(eventType, tourTitle, departureDate, username),
					LocalDateTime.now()
			);
			
			notificationClient.enqueueMail(request);
		} catch (Exception e) {
			log.error("Failed to send mail for booking {} event {}: {}", bookingId, eventType, e.getMessage(), e);
		}
	}

	private String buildSubject(String eventType, String tourTitle, LocalDateTime departureDate) {
		return switch (eventType) {
			case "BOOKING_EXPIRED" -> "Hết hạn giữ chỗ tour " + tourTitle;
			case "BOOKING_CANCELLED" -> "Hủy tour " + tourTitle;
			case "BOOKING_RESCHEDULED" -> "Đổi lịch tour " + tourTitle;
			case "REFUND_PROCESSED" -> "Hoàn tiền tour " + tourTitle;
			default -> "Thông báo tour " + tourTitle;
		};
	}

	private String buildHtml(String eventType, String tourTitle, LocalDateTime departureDate, String username) {
		return "<html><body>" +
				"<h1>" + buildSubject(eventType, tourTitle, departureDate) + "</h1>" +
				"<p>Kính gửi " + username + ",</p>" +
				"<p>Tour: " + tourTitle + "</p>" +
				"<p>Khởi hành: " + departureDate + "</p>" +
				"</body></html>";
	}

	private String buildText(String eventType, String tourTitle, LocalDateTime departureDate, String username) {
		return buildSubject(eventType, tourTitle, departureDate) + "\n" +
				"Kính gửi " + username + ",\n" +
				"Tour: " + tourTitle + "\n" +
				"Khởi hành: " + departureDate + "\n";
	}

	private String toJson(Map<String, Object> map) {
		try {
			return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(map);
		} catch (Exception e) {
			throw new RuntimeException("Failed to serialize payload", e);
		}
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> parseJson(String json) {
		try {
			return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
		} catch (Exception e) {
			throw new RuntimeException("Failed to parse payload", e);
		}
	}
}