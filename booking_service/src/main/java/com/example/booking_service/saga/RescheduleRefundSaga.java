package com.example.booking_service.saga;

import com.example.booking_service.repository.httpclient.AuthInternalClient;
import com.example.booking_service.repository.httpclient.NotificationInternalClient;
import com.example.booking_service.repository.httpclient.PaymentInternalClient;
import com.example.booking_service.repository.httpclient.TourInternalClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class RescheduleRefundSaga {

	private final JdbcTemplate jdbc;
	private final TourInternalClient tourClient;
	private final PaymentInternalClient paymentClient;
	private final NotificationInternalClient notificationClient;
	private final AuthInternalClient authClient;

	public enum SagaType {
		RESCHEDULE,
		REFUND
	}

	public enum SagaState {
		STARTED,
		SOURCE_HOLD_RELEASED,
		TARGET_HOLD_CREATED,
		PAYMENT_REFUND_INITIATED,
		PAYMENT_REFUND_COMPLETED,
		INVOICE_UPDATED,
		COMPENSATING,
		COMPLETED,
		FAILED
	}

	@Transactional
	public String startRescheduleSaga(long changeId, int bookingId, int sourceDepartureId, int targetDepartureId, int seats, LocalDateTime sourceDepartureAt, LocalDateTime targetDepartureAt) {
		String sagaId = java.util.UUID.randomUUID().toString();
		
		Map<String, Object> payload = Map.of(
			"changeId", changeId,
			"bookingId", bookingId,
			"sourceDepartureId", sourceDepartureId,
			"targetDepartureId", targetDepartureId,
			"seats", seats,
			"sourceDepartureAt", sourceDepartureAt.toString(),
			"targetDepartureAt", targetDepartureAt.toString(),
			"type", "RESCHEDULE"
		);
		
		String jsonPayload = toJson(payload);
		
		jdbc.update("""
			INSERT INTO booking_saga (id, saga_type, aggregate_id, status, current_step, payload, attempts, created_at, updated_at)
			VALUES (?, 'RESCHEDULE', ?, 'STARTED', 'STARTED', ?, 0, ?, ?)
			""", sagaId, String.valueOf(bookingId), jsonPayload, LocalDateTime.now(), LocalDateTime.now());
		
		log.info("Started reschedule saga {} for booking {} (change {})", sagaId, bookingId, changeId);
		return sagaId;
	}

	@Transactional
	public String startRefundSaga(long changeId, int bookingId, long refundAmount, String refundReference) {
		String sagaId = java.util.UUID.randomUUID().toString();
		
		Map<String, Object> payload = Map.of(
			"changeId", changeId,
			"bookingId", bookingId,
			"refundAmount", refundAmount,
			"refundReference", refundReference,
			"type", "REFUND"
		);
		
		String jsonPayload = toJson(payload);
		
		jdbc.update("""
			INSERT INTO booking_saga (id, saga_type, aggregate_id, status, current_step, payload, attempts, created_at, updated_at)
			VALUES (?, 'REFUND', ?, 'STARTED', 'STARTED', ?, 0, ?, ?)
			""", sagaId, String.valueOf(bookingId), jsonPayload, LocalDateTime.now(), LocalDateTime.now());
		
		log.info("Started refund saga {} for booking {} (change {})", sagaId, bookingId, changeId);
		return sagaId;
	}

	@Transactional
	public void executeRescheduleSaga(String sagaId) {
		var saga = jdbc.queryForMap("SELECT * FROM booking_saga WHERE id = ?", sagaId);
		if (saga == null) {
			log.warn("Saga not found: {}", sagaId);
			return;
		}
		
		Map<String, Object> payload = parseJson((String) saga.get("payload"));
		String type = (String) payload.get("type");
		
		if ("RESCHEDULE".equals(type)) {
			executeRescheduleSteps(sagaId, payload);
		} else if ("REFUND".equals(type)) {
			executeRefundSteps(sagaId, payload);
		}
	}

	private void executeRescheduleSteps(String sagaId, Map<String, Object> payload) {
		int bookingId = ((Number) payload.get("bookingId")).intValue();
		int sourceDepartureId = ((Number) payload.get("sourceDepartureId")).intValue();
		int targetDepartureId = ((Number) payload.get("targetDepartureId")).intValue();
		int seats = ((Number) payload.get("seats")).intValue();
		long changeId = ((Number) payload.get("changeId")).longValue();
		
		try {
			releaseHold(sourceDepartureId, seats);
			jdbc.update("UPDATE booking_saga SET status = 'SOURCE_HOLD_RELEASED', current_step = 'SOURCE_HOLD_RELEASED', updated_at = ? WHERE id = ?", LocalDateTime.now(), sagaId);
			
			createHold(targetDepartureId, seats, bookingId);
			jdbc.update("UPDATE booking_saga SET status = 'TARGET_HOLD_CREATED', current_step = 'TARGET_HOLD_CREATED', updated_at = ? WHERE id = ?", LocalDateTime.now(), sagaId);
			
			jdbc.update("UPDATE booking SET tour_departure_id = ? WHERE id = ?", targetDepartureId, bookingId);
			jdbc.update("UPDATE booking_reservation SET departure_id = ?, departure_at = ?, updated_at = ? WHERE booking_id = ?", 
					targetDepartureId, payload.get("targetDepartureAt"), LocalDateTime.now(), bookingId);
			
			jdbc.update("UPDATE booking_change SET status = 'APPROVED', applied_at = ?, active_key = NULL, version = version + 1 WHERE id = ?", LocalDateTime.now(), changeId);
			
			sendMail(bookingId, "BOOKING_RESCHEDULED");
			
			jdbc.update("UPDATE booking_saga SET status = 'COMPLETED', current_step = 'COMPLETED', updated_at = ? WHERE id = ?", LocalDateTime.now(), sagaId);
			log.info("Reschedule saga {} completed for booking {}", sagaId, bookingId);
			
		} catch (Exception e) {
			log.error("Reschedule saga {} failed: {}", sagaId, e.getMessage(), e);
			compensateReschedule(sagaId, payload, e.getMessage());
		}
	}

	private void executeRefundSteps(String sagaId, Map<String, Object> payload) {
		int bookingId = ((Number) payload.get("bookingId")).intValue();
		long refundAmount = ((Number) payload.get("refundAmount")).longValue();
		String refundReference = (String) payload.get("refundReference");
		long changeId = ((Number) payload.get("changeId")).longValue();
		
		try {
			paymentClient.refund(bookingId, refundAmount, refundReference);
			jdbc.update("UPDATE booking_saga SET status = 'PAYMENT_REFUND_INITIATED', current_step = 'PAYMENT_REFUND_INITIATED', updated_at = ? WHERE id = ?", LocalDateTime.now(), sagaId);
			
			// Payment service handles refund event propagation to invoice_service asynchronously.
			jdbc.update("UPDATE booking_saga SET status = 'INVOICE_UPDATED', current_step = 'INVOICE_UPDATED', updated_at = ? WHERE id = ?", LocalDateTime.now(), sagaId);
			
			jdbc.update("UPDATE booking_change SET status = 'REFUNDED', refund_recorded_at = ?, refund_amount = ?, refund_reference = ?, refund_recorded_by = ?, version = version + 1 WHERE id = ?", 
					LocalDateTime.now(), refundAmount, refundReference, 0, changeId);
			
			sendMail(bookingId, "REFUND_PROCESSED");
			
			jdbc.update("UPDATE booking_saga SET status = 'COMPLETED', current_step = 'COMPLETED', updated_at = ? WHERE id = ?", LocalDateTime.now(), sagaId);
			log.info("Refund saga {} completed for booking {}", sagaId, bookingId);
			
		} catch (Exception e) {
			log.error("Refund saga {} failed: {}", sagaId, e.getMessage(), e);
			compensateRefund(sagaId, payload, e.getMessage());
		}
	}

	private void compensateReschedule(String sagaId, Map<String, Object> payload, String error) {
		int bookingId = ((Number) payload.get("bookingId")).intValue();
		int sourceDepartureId = ((Number) payload.get("sourceDepartureId")).intValue();
		int targetDepartureId = ((Number) payload.get("targetDepartureId")).intValue();
		int seats = ((Number) payload.get("seats")).intValue();
		
		try {
			jdbc.update("UPDATE booking_saga SET status = 'COMPENSATING', current_step = 'COMPENSATING', last_error = ?, updated_at = ? WHERE id = ?", error, LocalDateTime.now(), sagaId);
			
			// Try to release target hold
			releaseHold(targetDepartureId, seats);
			
			// Try to restore source hold
			createHold(sourceDepartureId, seats, bookingId);
			
			// Revert booking
			jdbc.update("UPDATE booking SET tour_departure_id = ? WHERE id = ?", sourceDepartureId, bookingId);
			jdbc.update("UPDATE booking_reservation SET departure_id = ?, departure_at = ?, updated_at = ? WHERE booking_id = ?", 
					sourceDepartureId, payload.get("sourceDepartureAt"), LocalDateTime.now(), bookingId);
			
			jdbc.update("UPDATE booking_saga SET status = 'COMPLETED', current_step = 'COMPENSATED', updated_at = ? WHERE id = ?", LocalDateTime.now(), sagaId);
			log.info("Reschedule saga {} compensated for booking {}", sagaId, bookingId);
			
		} catch (Exception e) {
			log.error("Compensation failed for reschedule saga {}: {}", sagaId, e.getMessage(), e);
			jdbc.update("UPDATE booking_saga SET status = 'FAILED', current_step = 'COMPENSATION_FAILED', last_error = ?, updated_at = ? WHERE id = ?", e.getMessage(), LocalDateTime.now(), sagaId);
		}
	}

	private void compensateRefund(String sagaId, Map<String, Object> payload, String error) {
		try {
			jdbc.update("UPDATE booking_saga SET status = 'COMPENSATING', current_step = 'COMPENSATING', last_error = ?, updated_at = ? WHERE id = ?", error, LocalDateTime.now(), sagaId);
			
			// Refund compensation would require re-charging the customer
			// This is a manual process - mark as failed for manual intervention
			jdbc.update("UPDATE booking_saga SET status = 'FAILED', current_step = 'COMPENSATION_FAILED', last_error = ?, updated_at = ? WHERE id = ?", "Refund compensation requires manual intervention: " + error, LocalDateTime.now(), sagaId);
			log.warn("Refund saga {} requires manual compensation: {}", sagaId, error);
			
		} catch (Exception e) {
			log.error("Compensation failed for refund saga {}: {}", sagaId, e.getMessage(), e);
			jdbc.update("UPDATE booking_saga SET status = 'FAILED', current_step = 'COMPENSATION_FAILED', last_error = ?, updated_at = ? WHERE id = ?", e.getMessage(), LocalDateTime.now(), sagaId);
		}
	}

	private void releaseHold(int departureId, int seats) {
		try {
			tourClient.releaseHold(departureId, seats);
		} catch (Exception e) {
			log.warn("Failed to release hold via Tour internal API for departure {}: {}", departureId, e.getMessage());
			throw new RuntimeException("Failed to release hold via Tour internal API", e);
		}
	}

	private void createHold(int departureId, int seats, int bookingId) {
		try {
			var request = new TourInternalClient.HoldSeatsRequest(seats, String.valueOf(bookingId), LocalDateTime.now().plusMinutes(15));
			tourClient.holdSeats(departureId, request);
		} catch (Exception e) {
			log.warn("Failed to create hold via Tour internal API for departure {}: {}", departureId, e.getMessage());
			throw new RuntimeException("Failed to create hold on target departure", e);
		}
	}

	@Scheduled(fixedDelay = 60000) // Check every minute
	@Transactional
	public void processPendingSagas() {
		var pendingSagas = jdbc.queryForList("""
			SELECT * FROM booking_saga 
			WHERE saga_type IN ('RESCHEDULE', 'REFUND') 
			AND status IN ('STARTED', 'SOURCE_HOLD_RELEASED', 'TARGET_HOLD_CREATED', 'PAYMENT_REFUND_INITIATED')
			AND attempts < 3
			ORDER BY created_at
			LIMIT 50
			""");
		
		for (var saga : pendingSagas) {
			String sagaId = (String) saga.get("id");
			String status = (String) saga.get("status");
			int attempts = ((Number) saga.get("attempts")).intValue();
			
			try {
				executeRescheduleSaga(sagaId);
			} catch (Exception e) {
				log.error("Failed to process saga {}: {}", sagaId, e.getMessage(), e);
				jdbc.update("UPDATE booking_saga SET attempts = attempts + 1, last_error = ?, updated_at = ? WHERE id = ?", e.getMessage(), LocalDateTime.now(), sagaId);
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