package com.example.booking_service.operations;

import com.example.booking_service.reservation.BookingAccess;
import com.example.booking_service.repository.httpclient.InvoiceInternalClient;
import com.example.booking_service.repository.httpclient.NotificationInternalClient;
import com.example.booking_service.repository.httpclient.PaymentInternalClient;
import com.example.booking_service.repository.httpclient.TourInternalClient;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OperationsInsights {
	public record Review(LocalDateTime checkedAt, List<Map<String, Object>> items) {}
	private final JdbcTemplate jdbc;
	private final BookingAccess access;
	private final Clock clock;
	private final ReportingOperationsClient reportingClient;
	private final TourInternalClient tourClient;
	private final NotificationInternalClient notificationClient;
	private final PaymentInternalClient paymentClient;
	private final InvoiceInternalClient invoiceClient;
	private volatile Review lastReview;

	public OperationsInsights(JdbcTemplate jdbc, BookingAccess access, Clock clock,
							  ReportingOperationsClient reportingClient, TourInternalClient tourClient,
							  NotificationInternalClient notificationClient, PaymentInternalClient paymentClient,
							  InvoiceInternalClient invoiceClient) {
		this.jdbc = jdbc;
		this.access = access;
		this.clock = clock;
		this.reportingClient = reportingClient;
		this.tourClient = tourClient;
		this.notificationClient = notificationClient;
		this.paymentClient = paymentClient;
		this.invoiceClient = invoiceClient;
	}

	@Transactional(readOnly = true, timeout = 5)
	public Map<String, Object> report(LocalDate from, LocalDate through) {
		require("BOOKINGS_GET_ALL");
		require("INVOICES_GET_ALL");
		if (from == null || through == null || through.isBefore(from) || through.isAfter(from.plusDays(30)))
			throw new IllegalArgumentException("Chỉ chọn khoảng báo cáo tối đa 31 ngày");
		var reported = reportingClient.report(from, through);
		if (reported.isPresent()) return reported.get();
		// Booking-local fallback when reporting_service is disabled: never join foreign DB tables.
		var start = from.atStartOfDay();
		var until = through.plusDays(1).atStartOfDay();
		return jdbc.queryForMap("""
				SELECT
				  totals.bookings, totals.expired,
				  0 AS collected,
				  (SELECT COALESCE(SUM(refund_amount),0) FROM booking_change
					WHERE status='REFUNDED' AND updated_at>=? AND updated_at<?) AS schedule_refunds,
				  0 AS reconciliation_refunds,
				  (SELECT COUNT(*) FROM customer c JOIN booking b ON b.id=c.booking_id
					WHERE b.status='CONFIRMED' AND b.tour_departure_id IS NOT NULL) AS confirmed_passengers
				FROM (SELECT COUNT(*) AS bookings,
						  COALESCE(SUM(CASE WHEN status='EXPIRED' THEN 1 ELSE 0 END),0) AS expired
					FROM booking WHERE created_at>=? AND created_at<?) totals
				""", start, until, start, until);
	}

	public List<Map<String, Object>> delivery(int bookingId) {
		require("BOOKINGS_GET_ALL");
		var rows = notificationClient.getDeliveries(bookingId);
		return wallClock(rows, "scheduled_at", "sent_at", "updated_at");
	}

	public void retryDelivery(int bookingId, long id) {
		if (!access.admin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cần quyền quản trị để gửi lại");
		try {
			notificationClient.retryMail(bookingId, id);
		} catch (feign.FeignException.Conflict conflict) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Chỉ gửi lại email đã xác nhận thất bại");
		}
	}

	public List<Map<String, Object>> audit(int page) {
		if (!access.admin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cần quyền quản trị để xem nhật ký");
		if (page < 0 || page > 10000) throw new IllegalArgumentException("Trang nhật ký không hợp lệ");
		var rows = jdbc.queryForList("""
				SELECT id,area,target_id,actor_id,before_value,after_value,created_at
				FROM operation_audit ORDER BY id DESC LIMIT 21 OFFSET ?
				""", page * 20);
		return wallClock(rows, "created_at");
	}

	private static List<Map<String, Object>> wallClock(List<Map<String, Object>> rows, String... fields) {
		for (var row : rows) {
			for (var field : fields) {
				if (row.get(field) instanceof java.sql.Timestamp time)
					row.put(field, time.toLocalDateTime().toString());
			}
		}
		return rows;
	}

	@Transactional(readOnly = true, timeout = 5)
	public Review integrity() {
		require("BOOKINGS_GET_ALL");
		var current = lastReview;
		if (current != null) return current;
		return scheduledReview();
	}

	@Scheduled(initialDelayString = "${app.operations.review-delay-ms:300000}",
			   fixedDelayString = "${app.operations.review-delay-ms:300000}")
	@Transactional(readOnly = true, timeout = 5)
	public synchronized Review scheduledReview() {
		var checkedAt = LocalDateTime.now(clock);
		if (lastReview != null && lastReview.checkedAt().isAfter(checkedAt.minusSeconds(30))) return lastReview;
		
		// Get recent bookings
		var recentRows = jdbc.queryForList("""
			SELECT id,tour_departure_id,status FROM booking ORDER BY id DESC LIMIT 200
			""");
		
		var items = new java.util.ArrayList<Map<String, Object>>();
		for (var row : recentRows) {
			String status = (String) row.get("status");
			if (!List.of("CONFIRMED", "PENDING_PAYMENT").contains(status)) continue;
			
			Integer departureId = (Integer) row.get("tour_departure_id");
			if (departureId == null) {
				items.add(Map.of(
					"booking_id", row.get("id"),
					"tour_departure_id", departureId,
					"booking_status", status,
					"issue", "MISSING_DEPARTURE"
				));
				continue;
			}
			
			// Get departure info via Tour internal API
			Map<String, Object> departure;
			try {
				departure = tourClient.getDeparture(departureId);
			} catch (Exception e) {
				items.add(Map.of(
					"booking_id", row.get("id"),
					"tour_departure_id", departureId,
					"booking_status", status,
					"issue", "MISSING_DEPARTURE"
				));
				continue;
			}
			if (departure == null || departure.isEmpty()) {
				items.add(Map.of(
					"booking_id", row.get("id"),
					"tour_departure_id", departureId,
					"booking_status", status,
					"issue", "MISSING_DEPARTURE"
				));
				continue;
			}
			
			String operationalStatus = (String) departure.get("operational_status");
			if (operationalStatus == null) operationalStatus = "OPEN";
			
			// Get reservation info
			var reservationRows = jdbc.queryForList("""
				SELECT status,expires_at FROM booking_reservation WHERE booking_id=?
				""", row.get("id"));
			String reservationStatus = null;
			LocalDateTime expiresAt = null;
			if (!reservationRows.isEmpty()) {
				reservationStatus = (String) reservationRows.getFirst().get("status");
				Object exp = reservationRows.getFirst().get("expires_at");
				expiresAt = exp instanceof LocalDateTime ? (LocalDateTime) exp : ((java.sql.Timestamp) exp).toLocalDateTime();
			}
			
			// Check payment/invoice via internal APIs
			Integer bookingId = ((Number) row.get("id")).intValue();
			boolean paidPayment = false;
			boolean openReconciliation = false;
			boolean inFlight = false;
			try {
				Map<String, Object> payEv = paymentClient.getBookingPaymentEvidence(bookingId);
				if (payEv != null) {
					paidPayment = Boolean.TRUE.equals(payEv.get("paidPayment"));
					openReconciliation = Boolean.TRUE.equals(payEv.get("openReconciliation"));
					inFlight = Boolean.TRUE.equals(payEv.get("inFlight"));
				}
			} catch (Exception ignored) {
				// If payment service unavailable, skip false-positive integrity alerts for this booking
				continue;
			}
			boolean paidInvoice = false;
			try {
				Map<String, Object> inv = invoiceClient.getInvoiceByBookingId(bookingId);
				paidInvoice = inv != null && "PAID".equals(inv.get("status"));
			} catch (feign.FeignException.NotFound ignored) {
				paidInvoice = false;
			} catch (Exception ignored) {
				continue;
			}

			String issue = null;
			if ("PAID".equals(reservationStatus) || paidPayment) {
				if (!paidInvoice) issue = "MISSING_PAID_INVOICE";
			}
			if (paidInvoice && !paidPayment) issue = "MISSING_PAID_PAYMENT";

			if (List.of("HELD", "CHECKOUT").contains(reservationStatus) && expiresAt != null) {
				if (expiresAt.isBefore(checkedAt.minusMinutes(10))
						&& "OPEN".equals(operationalStatus) && !paidPayment && !paidInvoice
						&& !openReconciliation && !inFlight) {
					issue = "OVERDUE_HOLD";
				}
			}
			
			if (issue != null) {
				items.add(Map.of(
					"booking_id", row.get("id"),
					"tour_departure_id", departureId,
					"booking_status", status,
					"issue", issue
				));
			}
		}
		
		lastReview = new Review(checkedAt, items);
		return lastReview;
	}

	private void require(String permission) {
		if (!access.can(permission)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn chưa có quyền xem mục này");
	}
}