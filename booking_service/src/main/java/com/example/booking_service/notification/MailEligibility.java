package com.example.booking_service.notification;

import com.example.booking_service.repository.httpclient.AuthInternalClient;
import com.example.booking_service.repository.httpclient.TourInternalClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;

@Component
public class MailEligibility {
	private final JdbcTemplate jdbc;
	private final Clock clock;
	private final AuthInternalClient authClient;
	private final TourInternalClient tourClient;
	
	public MailEligibility(JdbcTemplate jdbc, Clock clock, AuthInternalClient authClient, TourInternalClient tourClient) { 
		this.jdbc = jdbc; 
		this.clock = clock; 
		this.authClient = authClient;
		this.tourClient = tourClient;
	}

	public String skipReason(MailOutboxItem item, MailPayload payload) {
		if (item.eventType() == MailEventType.TEST_MAIL) return null;
		if (item.eventType() == MailEventType.BOOKING_CHANGE_UPDATED) {
			var changes=jdbc.queryForList("""
				SELECT c.*,b.tour_departure_id AS current_departure_id,b.status AS current_booking_status,
					(SELECT MAX(later.applied_at) FROM booking_change later WHERE later.booking_id=c.booking_id) AS latest_applied_at
				FROM booking_change c JOIN booking b ON b.id=c.booking_id
				WHERE c.id=? AND c.booking_id=? AND c.account_id=?
				""",payload.changeId(),item.bookingId(),item.accountId());
			if(changes.size()!=1)return "Change request no longer exists or owner changed";
			var c=changes.getFirst();
			
			// Get operational status via Tour internal API
			String operationalStatus = "OPEN";
			Integer currentDepartureId = (Integer) c.get("current_departure_id");
			if (currentDepartureId != null) {
				try {
					Map<String, Object> departure = tourClient.getDeparture(currentDepartureId);
					Object status = departure.get("operational_status");
					if (status != null) operationalStatus = status.toString();
				} catch (Exception ignored) {}
			}
			
			if("APPROVED".equals(payload.changeStatus()) && (!"CONFIRMED".equals(c.get("current_booking_status"))
				|| ((Number)c.get("current_departure_id")).intValue()!=payload.departureId()
				|| java.util.List.of("SUSPENDED","CANCELLED").contains(operationalStatus)
				|| !Objects.equals(c.get("applied_at"),c.get("latest_applied_at"))))
				return "Confirmed schedule was superseded by another change";
			
			// Get user email via Auth internal API
			String email;
			try {
				Map<String, Object> user = authClient.getUserById((long) item.accountId());
				email = (String) user.get("email");
			} catch (Exception e) {
				return "User no longer exists";
			}
			
			return item.recipient().equalsIgnoreCase(email)
				&& Objects.equals(c.get("status"),payload.changeStatus())
				&& Objects.equals(((Number)c.get("target_departure_id")).intValue(),payload.departureId())
				&& com.example.booking_service.change.ChangeLedger.date(c.get("target_departure_at")).equals(payload.departureAt())
				? null : "Schedule notice was superseded";
		}
		if (item.eventType() == MailEventType.PAYMENT_REMINDER || item.eventType() == MailEventType.DEPARTURE_REMINDER) {
			var state=new ReminderStateReader(jdbc, authClient, tourClient).read(item.bookingId());
			if(state.isEmpty()) return "Reminder booking no longer exists";
			var s=state.get();
			if(!Objects.equals(item.accountId(),s.accountId()) || !item.recipient().equalsIgnoreCase(s.recipient())
					|| !Objects.equals(payload.departureId(),s.departureId()) || !Objects.equals(payload.departureAt(),s.departureAt()))
				return "Reminder owner, email or departure changed";
			var selection=new ReminderRules().select(s,LocalDateTime.now(clock));
			if(selection.isEmpty()) return "Reminder is no longer due or valid";
			var current=selection.get();
			return current.type()==item.eventType() && Objects.equals(current.due(),payload.reminderAt())
					&& Objects.equals(current.daysBefore(),payload.daysBefore()) && Objects.equals(current.deadline(),payload.holdUntil())
					? null : "Reminder was superseded by a newer milestone";
		}
		// Get booking basic info
		var bookingRows = jdbc.queryForList("SELECT status, account_id, tour_departure_id FROM booking WHERE id=?", item.bookingId());
		if (bookingRows.isEmpty()) return "Booking no longer exists";
		var booking = bookingRows.getFirst();
		
		// Get user info via Auth internal API
		String email;
		try {
			Map<String, Object> user = authClient.getUserById((long) item.accountId());
			email = (String) user.get("email");
		} catch (Exception e) {
			return "Booking, departure or account no longer exists";
		}
		
		// Get departure info via Tour internal API
		String operationalStatus = "OPEN";
		LocalDateTime departureDate = null;
		Integer departureId = (Integer) booking.get("tour_departure_id");
		if (departureId != null) {
			try {
				Map<String, Object> departure = tourClient.getDeparture(departureId);
				Object status = departure.get("operational_status");
				if (status != null) operationalStatus = status.toString();
				Object depDate = departure.get("departure_date");
				departureDate = depDate instanceof LocalDateTime ? (LocalDateTime) depDate : ((java.sql.Timestamp) depDate).toLocalDateTime();
			} catch (Exception e) {
				return "Booking, departure or account no longer exists";
			}
		}
		
		if (!Objects.equals(item.accountId(), ((Number)booking.get("account_id")).intValue())
				|| !item.recipient().equalsIgnoreCase(email))
			return "Booking owner or email changed";
		int payments = jdbc.queryForObject("SELECT COUNT(*) FROM payment WHERE booking_id=? AND account_id=? AND status='PAID'",
				Integer.class, item.bookingId(), item.accountId());
		int invoices = jdbc.queryForObject("SELECT COUNT(*) FROM invoice WHERE booking_id=? AND account_id=? AND status='PAID'",
				Integer.class, item.bookingId(), item.accountId());
		boolean paid = payments > 0 && invoices > 0;
		if ((payments > 0) != (invoices > 0)) return "Payment and invoice require reconciliation";
		boolean active = java.util.List.of("PENDING_PAYMENT", "CONFIRMED").contains(String.valueOf(booking.get("status")));
		if (item.eventType() == MailEventType.BOOKING_CANCELLED || item.eventType() == MailEventType.BOOKING_EXPIRED)
			return active ? "Booking is no longer cancelled" : null;
		if (item.eventType() == MailEventType.PAYMENT_SUCCEEDED) {
			if(java.util.List.of("SUSPENDED","CANCELLED").contains(operationalStatus)) return "Receipt departure suspended; contact shop";
			if(!Objects.equals(payload.departureId(), departureId)
				|| !Objects.equals(payload.departureAt(), departureDate))
				return "Receipt departure changed; see schedule confirmation";
			// Receipts are emitted for both providers; the reference is unique per provider table.
			int settled = jdbc.queryForObject("""
				SELECT (SELECT COUNT(*) FROM vnpay_transaction WHERE booking_id=? AND account_id=?
							AND txn_ref=? AND status='PAID' AND amount_vnd=?)
					 + (SELECT COUNT(*) FROM momo_transaction WHERE booking_id=? AND account_id=?
							AND order_id=? AND status='PAID' AND amount_vnd=?)
				""", Integer.class, item.bookingId(), item.accountId(), payload.transactionRef(), payload.amountVnd(),
					item.bookingId(), item.accountId(), payload.transactionRef(), payload.amountVnd());
			return paid && settled == 1 ? null : "Verified payment no longer matches event";
		}
		if (!active) return "Booking is cancelled";
		if(java.util.List.of("SUSPENDED","CANCELLED").contains(operationalStatus)) return "Departure suspended";
		if (payload.departureId() == null
				|| payload.departureId() != departureId)
			return "Departure changed";
		if (departureDate == null || !departureDate.equals(payload.departureAt())) return "Departure date changed";
		if (!departureDate.isAfter(LocalDateTime.now(clock))) return "Tour has departed";
		if (item.eventType() == MailEventType.BOOKING_CREATED && payload.holdUntil() != null) {
			var holds = jdbc.queryForList("SELECT status,expires_at FROM booking_reservation WHERE booking_id=?", item.bookingId());
			if (holds.size() != 1 || !"HELD".equals(holds.getFirst().get("status"))) return "Reservation no longer held";
			Object expiry = holds.getFirst().get("expires_at");
			LocalDateTime until = expiry instanceof LocalDateTime value ? value : ((java.sql.Timestamp)expiry).toLocalDateTime();
			if (!until.equals(payload.holdUntil()) || !until.isAfter(LocalDateTime.now(clock))) return "Reservation deadline changed or expired";
		}
		return switch (item.eventType()) {
			case BOOKING_CREATED -> paid ? "Unpaid acknowledgement is obsolete" : null;
			default -> "Unsupported mail event";
		};
	}
}