package com.example.booking_service.advice;

import com.example.booking_service.reservation.BookingAccess;
import com.example.booking_service.repository.httpclient.TourInternalClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service
public class BookingAdviceService {
	public static final String RULE_VERSION = "reservation-v1";
	private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
	private static final int PAYMENT_INFLIGHT_GRACE_MINUTES = 5;
	public record Item(int bookingId, int tourId, String tourProgram, String tourTitle,
			OffsetDateTime createdAt, OffsetDateTime departureAt, String state, String reservationStatus,
			OffsetDateTime paymentDeadline, long remainingSeconds, OffsetDateTime checkedAt, String ruleVersion) {}
	public record Page(List<Item> bookings, boolean hasMore, OffsetDateTime checkedAt) {}
	private final JdbcTemplate jdbc;
	private final BookingAccess access;
	private final Clock clock;
	private final TourInternalClient tourClient;

	public BookingAdviceService(JdbcTemplate jdbc, BookingAccess access, Clock clock, TourInternalClient tourClient) {
		this.jdbc=jdbc; this.access=access; this.clock=clock; this.tourClient=tourClient;
	}
	@Transactional(readOnly=true)
	public Page mine(Integer bookingId) {
		int owner=access.accountId();
		if (bookingId!=null && bookingId<1) throw new IllegalArgumentException("Mã booking không hợp lệ");
		var now=OffsetDateTime.ofInstant(clock.instant(),ZONE);
		var paymentCutoff = now.toLocalDateTime().minusMinutes(PAYMENT_INFLIGHT_GRACE_MINUTES);
		
		// Base query without cross-DB JOINs
		String sql="""
			SELECT b.id,b.created_at,b.status AS booking_status,b.tour_departure_id,
			  (SELECT COUNT(*) FROM booking_change c WHERE c.booking_id=b.id AND c.status='REFUNDED') AS refund_count,
			  (SELECT COUNT(*) FROM booking_change c WHERE c.booking_id=b.id AND c.source_departure_id=b.tour_departure_id
				  AND c.kind='SHOP' AND c.active_key=1) AS active_shop_offer,
			  r.status AS reservation_status,r.expires_at,
			  r.departure_id AS held_departure_id,r.departure_at AS held_departure_at,
			  (SELECT COUNT(*) FROM payment p WHERE p.booking_id=b.id AND p.account_id=b.account_id AND p.status='PAID') AS payments,
			  (SELECT COUNT(*) FROM invoice i WHERE i.booking_id=b.id AND i.account_id=b.account_id AND i.status='PAID') AS invoices,
			  ((SELECT COUNT(*) FROM payment p WHERE p.booking_id=b.id AND p.status='PAID')
				+(SELECT COUNT(*) FROM invoice i WHERE i.booking_id=b.id AND i.status='PAID')
				+(SELECT COUNT(*) FROM vnpay_transaction v WHERE v.booking_id=b.id AND v.status='PAID')
				+(SELECT COUNT(*) FROM momo_transaction m WHERE m.booking_id=b.id AND m.status='PAID')) AS paid_evidence,
			  (SELECT COUNT(*) FROM payment_reconciliation q WHERE q.booking_id=b.id AND q.status='OPEN') AS reconciliation,
			  ((SELECT COUNT(*) FROM vnpay_transaction v WHERE v.booking_id=b.id AND v.account_id=b.account_id
					AND v.status='PENDING' AND v.expires_at>?)
				+(SELECT COUNT(*) FROM momo_transaction m WHERE m.booking_id=b.id AND m.account_id=b.account_id
					AND m.status='PENDING' AND m.expires_at>?)) AS pending_payments,
			  (SELECT MAX(total_booking_tour_expense) FROM payment p WHERE p.booking_id=b.id AND p.account_id=b.account_id AND p.status='PAID') AS payment_amount,
			  (SELECT MAX(total_booking_tour_expense) FROM invoice i WHERE i.booking_id=b.id AND i.account_id=b.account_id AND i.status='PAID') AS invoice_amount
			FROM booking b
			LEFT JOIN booking_reservation r ON r.booking_id=b.id WHERE b.account_id=?
			""";
		var rows=bookingId==null ? jdbc.queryForList(sql+" ORDER BY b.id DESC LIMIT 21",paymentCutoff,paymentCutoff,owner)
				:jdbc.queryForList(sql+" AND b.id=?",paymentCutoff,paymentCutoff,owner,bookingId);
		return new Page(rows.stream().limit(20).map(row->project(row,now)).toList(),rows.size()>20,now);
	}
	private Item project(Map<String,Object> r,OffsetDateTime now) {
		var created=date(r.get("created_at"));
		int departureId = number(r, "tour_departure_id");
		var departure = dateFromDeparture(departureId);
		var deadline=date(r.get("expires_at")); String hold=(String)r.get("reservation_status");
		boolean matches=r.get("held_departure_id")==null || (number(r,"held_departure_id")==departureId
				&& Objects.equals(date(r.get("held_departure_at")),departure));
		var paid=amount(r.get("payment_amount")); var invoice=amount(r.get("invoice_amount"));
		boolean complete=number(r,"payments")==1 && number(r,"invoices")==1 && paid!=null && paid.signum()>0
				&& invoice!=null && paid.compareTo(invoice)==0;
		boolean expired = "EXPIRED".equals(hold) || "EXPIRED".equals(r.get("booking_status"));
		boolean cancelled = "CANCELLED".equals(hold) || "CANCELLED".equals(r.get("booking_status"));
		boolean confirmedPaid = complete && "CONFIRMED".equals(r.get("booking_status"))
				&& (hold == null || "PAID".equals(hold));
		String operationalStatus = getDepartureOperationalStatus(departureId);
		
		String state;
		if (!matches || number(r,"reconciliation")>0) state="REVIEW_REQUIRED";
		else if(number(r,"refund_count")>0) state="REFUNDED";
		else if (!confirmedPaid && (number(r,"paid_evidence")>0 || "PAID".equals(hold))) state="REVIEW_REQUIRED";
		else if (expired) state="EXPIRED";
		else if (cancelled) state="CANCELLED";
		else if ("SUSPENDED".equals(operationalStatus))
			state=number(r,"active_shop_offer")>0 ? "SCHEDULE_CHANGE_REQUIRED" : "DEPARTURE_SUSPENDED";
		else if ("CANCELLED".equals(operationalStatus)) state="DEPARTURE_CANCELLED";
		else if (confirmedPaid) state="PAID";
		else if ("CHECKOUT".equals(hold) && number(r,"pending_payments")>0) state="PAYMENT_PENDING";
		else if (departure==null || !departure.isAfter(now)) state="DEPARTURE_PASSED";
		else if (hold==null) state="LEGACY_UNVERIFIED";
		else if (deadline==null || created==null || !deadline.isAfter(created) || (departure!=null && deadline.isAfter(departure))) state="REVIEW_REQUIRED";
		else if (!List.of("HELD","CHECKOUT").contains(hold)) state="REVIEW_REQUIRED";
		else if (!deadline.isAfter(now)) state="DEADLINE_PASSED";
		else state="CHECKOUT".equals(hold)?"CHECKOUT":"HELD";
		long remaining=List.of("HELD","CHECKOUT").contains(state)?Math.max(0,Duration.between(now,deadline).getSeconds()):0;
		
		// Get tour info via Tour internal API
		String tourProgram = "", tourTitle = "";
		int tourId = 0;
		try {
			Map<String, Object> departureInfo = tourClient.getDeparture(departureId);
			tourId = ((Number) departureInfo.get("tour_id")).intValue();
			tourProgram = "Tour #" + tourId;
			tourTitle = "Tour #" + tourId;
		} catch (Exception ignored) {
		}
		
		return new Item(number(r,"id"),tourId,tourProgram,tourTitle,
				created,departure,state,hold,deadline,remaining,now,RULE_VERSION);
	}
	
	private OffsetDateTime dateFromDeparture(int departureId) {
		try {
			Map<String, Object> departure = tourClient.getDeparture(departureId);
			Object depDate = departure.get("departure_date");
			if (depDate instanceof LocalDateTime ld) return ld.atZone(ZONE).toOffsetDateTime();
			if (depDate instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().atZone(ZONE).toOffsetDateTime();
		} catch (Exception ignored) {}
		return null;
	}
	
	private String getDepartureOperationalStatus(int departureId) {
		try {
			Map<String, Object> departure = tourClient.getDeparture(departureId);
			Object status = departure.get("operational_status");
			return status != null ? status.toString() : "OPEN";
		} catch (Exception ignored) {
			return "OPEN";
		}
	}

	private static int number(Map<String,Object> row,String key) { return ((Number)row.get(key)).intValue(); }
	private static BigDecimal amount(Object value) { return value==null?null:new BigDecimal(value.toString()); }
	private static OffsetDateTime date(Object value) {
		if(value==null) return null;
		LocalDateTime time=value instanceof LocalDateTime t?t:((java.sql.Timestamp)value).toLocalDateTime();
		return time.atZone(ZONE).toOffsetDateTime();
	}
}