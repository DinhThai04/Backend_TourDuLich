package com.example.booking_service.operations;

import com.example.booking_service.reservation.BookingAccess;
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
	private volatile Review lastReview;

	public OperationsInsights(JdbcTemplate jdbc, BookingAccess access, Clock clock,
							  ReportingOperationsClient reportingClient, TourInternalClient tourClient) {
		this.jdbc = jdbc;
		this.access = access;
		this.clock = clock;
		this.reportingClient = reportingClient;
		this.tourClient = tourClient;
	}

	@Transactional(readOnly = true, timeout = 5)
	public Map<String, Object> report(LocalDate from, LocalDate through) {
		require("BOOKINGS_GET_ALL");
		require("INVOICES_GET_ALL");
		if (from == null || through == null || through.isBefore(from) || through.isAfter(from.plusDays(30)))
			throw new IllegalArgumentException("Chỉ chọn khoảng báo cáo tối đa 31 ngày");
		var reported = reportingClient.report(from, through);
		if (reported.isPresent()) return reported.get();
		var start = from.atStartOfDay();
		var until = through.plusDays(1).atStartOfDay();
		return jdbc.queryForMap("""
				SELECT
				  totals.bookings,totals.expired,
				  (SELECT COALESCE(SUM(total_booking_tour_expense),0) FROM payment
					WHERE status IN ('PAID','REFUNDED') AND day_of_pay>=? AND day_of_pay<?) AS collected,
				  (SELECT COALESCE(SUM(refund_amount),0) FROM booking_change
					WHERE status='REFUNDED' AND updated_at>=? AND updated_at<?) AS schedule_refunds,
				  (SELECT COALESCE(SUM(amount_vnd),0) FROM payment_reconciliation_event
					WHERE action='REFUND_RECORDED' AND created_at>=? AND created_at<?) AS reconciliation_refunds,
				  (SELECT COUNT(*) FROM customer c JOIN booking b ON b.id=c.booking_id
					WHERE b.status='CONFIRMED' AND b.tour_departure_id IS NOT NULL
					  AND EXISTS (SELECT 1 FROM invoice i WHERE i.booking_id=b.id AND i.status='PAID')) AS confirmed_passengers
				FROM (SELECT COUNT(*) AS bookings,
						  COALESCE(SUM(CASE WHEN status='EXPIRED' THEN 1 ELSE 0 END),0) AS expired
					FROM booking WHERE created_at>=? AND created_at<?) totals
				""", start, until, start, until, start, until, start, until, start, until);
	}

	public List<Map<String, Object>> delivery(int bookingId) {
		require("BOOKINGS_GET_ALL");
		var rows = jdbc.queryForList("""
				SELECT id,booking_id,event_type,status,attempt_count,scheduled_at,sent_at,updated_at
				FROM mail_outbox WHERE booking_id=? ORDER BY id DESC LIMIT 30
				""", bookingId);
		return wallClock(rows, "scheduled_at", "sent_at", "updated_at");
	}

	@Transactional
	public void retryDelivery(int bookingId, long id) {
		if (!access.admin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cần quyền quản trị để gửi lại");
		var now = LocalDateTime.now(clock);
		int changed = jdbc.update("""
				UPDATE mail_outbox SET status='RETRY',attempt_count=0,next_attempt_at=?,updated_at=?,last_error=NULL
				WHERE id=? AND booking_id=? AND status='FAILED' AND sent_at IS NULL
				""", now, now, id, bookingId);
		if (changed == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Chỉ gửi lại email đã xác nhận thất bại");
		jdbc.update("""
				INSERT INTO operation_audit(area,target_id,actor_id,before_value,after_value,created_at)
				VALUES ('MAIL_RETRY',?,?,'FAILED','RETRY',?)
				""", id, access.accountId(), now);
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
			
			// Check payment/invoice
			boolean paidPayment = jdbc.queryForObject("""
				SELECT EXISTS (SELECT 1 FROM payment p WHERE p.booking_id=? AND p.status='PAID')
				""", Boolean.class, row.get("id"));
			boolean paidInvoice = jdbc.queryForObject("""
				SELECT EXISTS (SELECT 1 FROM invoice i WHERE i.booking_id=? AND i.status='PAID')
				""", Boolean.class, row.get("id"));
			
			String issue = null;
			if ("PAID".equals(reservationStatus) || paidPayment) {
				if (!paidInvoice) issue = "MISSING_PAID_INVOICE";
			}
			if (paidInvoice && !paidPayment) issue = "MISSING_PAID_PAYMENT";
			
			if (List.of("HELD", "CHECKOUT").contains(reservationStatus) && expiresAt != null) {
				if (expiresAt.isBefore(checkedAt.minusMinutes(10))
						&& "OPEN".equals(operationalStatus) && !paidPayment && !paidInvoice
						&& jdbc.queryForObject("""
							SELECT NOT EXISTS (SELECT 1 FROM payment_reconciliation q WHERE q.booking_id=? AND q.status='OPEN')
							""", Boolean.class, row.get("id"))
						&& jdbc.queryForObject("""
							SELECT NOT EXISTS (SELECT 1 FROM vnpay_transaction v WHERE v.booking_id=? AND v.status='PENDING' AND v.expires_at>?)
							""", Boolean.class, row.get("id"), checkedAt.minusMinutes(5))
						&& jdbc.queryForObject("""
							SELECT NOT EXISTS (SELECT 1 FROM momo_transaction m WHERE m.booking_id=? AND m.status='PENDING' AND m.expires_at>?)
							""", Boolean.class, row.get("id"), checkedAt.minusMinutes(5))) {
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