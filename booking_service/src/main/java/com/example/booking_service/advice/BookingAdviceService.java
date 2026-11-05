package com.example.booking_service.advice;

import com.example.booking_service.reservation.BookingAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Owner-only projection. Reading it never sweeps expiry, changes seats or sends email. */
@Service
public class BookingAdviceService {
    public static final String RULE_VERSION = "reservation-v1";
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    public record Item(int bookingId, int tourId, String tourProgram, String tourTitle,
            OffsetDateTime createdAt, OffsetDateTime departureAt, String state, String reservationStatus,
            OffsetDateTime paymentDeadline, long remainingSeconds, OffsetDateTime checkedAt, String ruleVersion) {}
    public record Page(List<Item> bookings, boolean hasMore, OffsetDateTime checkedAt) {}
    private final JdbcTemplate jdbc;
    private final BookingAccess access;
    private final Clock clock;
    public BookingAdviceService(JdbcTemplate jdbc, BookingAccess access, Clock clock) {
        this.jdbc=jdbc; this.access=access; this.clock=clock;
    }
    @Transactional(readOnly=true)
    public Page mine(Integer bookingId) {
        int owner=access.accountId(); // No admin override and no caller-supplied account ID.
        if (bookingId!=null && bookingId<1) throw new IllegalArgumentException("Mã booking không hợp lệ");
        var now=OffsetDateTime.ofInstant(clock.instant(),ZONE);
        String sql="""
            SELECT b.id,b.created_at,b.status AS booking_status,t.id AS tour_id,t.tour_program,t.tour_title,
              d.id AS departure_id,d.departure_date,d.operational_status,
              (SELECT COUNT(*) FROM booking_change c WHERE c.booking_id=b.id AND c.status='REFUNDED') AS refund_count,
              r.status AS reservation_status,r.expires_at,
              r.departure_id AS held_departure_id,r.departure_at AS held_departure_at,
              (SELECT COUNT(*) FROM payment p WHERE p.booking_id=b.id AND p.account_id=b.account_id AND p.status='PAID') AS payments,
              (SELECT COUNT(*) FROM invoice i WHERE i.booking_id=b.id AND i.account_id=b.account_id AND i.status='PAID') AS invoices,
              ((SELECT COUNT(*) FROM payment p WHERE p.booking_id=b.id AND p.status='PAID')
                +(SELECT COUNT(*) FROM invoice i WHERE i.booking_id=b.id AND i.status='PAID')
                +(SELECT COUNT(*) FROM vnpay_transaction v WHERE v.booking_id=b.id AND v.status='PAID')
                +(SELECT COUNT(*) FROM momo_transaction m WHERE m.booking_id=b.id AND m.status='PAID')) AS paid_evidence,
              (SELECT COUNT(*) FROM payment_reconciliation q WHERE q.booking_id=b.id AND q.status='OPEN') AS reconciliation,
              (SELECT MAX(total_booking_tour_expense) FROM payment p WHERE p.booking_id=b.id AND p.account_id=b.account_id AND p.status='PAID') AS payment_amount,
              (SELECT MAX(total_booking_tour_expense) FROM invoice i WHERE i.booking_id=b.id AND i.account_id=b.account_id AND i.status='PAID') AS invoice_amount
            FROM booking b JOIN tour_departure d ON d.id=b.tour_departure_id JOIN tour t ON t.id=d.tour_id
            LEFT JOIN booking_reservation r ON r.booking_id=b.id WHERE b.account_id=?
            """;
        var rows=bookingId==null ? jdbc.queryForList(sql+" ORDER BY b.id DESC LIMIT 21",owner)
                :jdbc.queryForList(sql+" AND b.id=?",owner,bookingId);
        return new Page(rows.stream().limit(20).map(row->project(row,now)).toList(),rows.size()>20,now);
    }
    private Item project(Map<String,Object> r,OffsetDateTime now) {
        var created=date(r.get("created_at")); var departure=date(r.get("departure_date"));
        var deadline=date(r.get("expires_at")); String hold=(String)r.get("reservation_status");
        boolean matches=r.get("held_departure_id")==null || (number(r,"held_departure_id")==number(r,"departure_id")
                && Objects.equals(date(r.get("held_departure_at")),departure));
        var paid=amount(r.get("payment_amount")); var invoice=amount(r.get("invoice_amount"));
        boolean complete=number(r,"payments")==1 && number(r,"invoices")==1 && paid!=null && paid.signum()>0
                && invoice!=null && paid.compareTo(invoice)==0;
        String state;
        if (!matches || number(r,"reconciliation")>0) state="REVIEW_REQUIRED";
        else if(number(r,"refund_count")>0) state="REFUNDED";
        else if("SUSPENDED".equals(r.get("operational_status"))) state="SCHEDULE_CHANGE_REQUIRED";
        else if (complete && "CONFIRMED".equals(r.get("booking_status")) && (hold==null || "PAID".equals(hold))) state="PAID";
        else if (number(r,"paid_evidence")>0 || "PAID".equals(hold)) state="REVIEW_REQUIRED";
        else if ("EXPIRED".equals(hold) || "EXPIRED".equals(r.get("booking_status"))) state="EXPIRED";
        else if ("CANCELLED".equals(hold) || "CANCELLED".equals(r.get("booking_status"))) state="CANCELLED";
        else if (departure==null || !departure.isAfter(now)) state="DEPARTURE_PASSED";
        else if (hold==null) state="LEGACY_UNVERIFIED";
        else if (deadline==null || created==null || !deadline.isAfter(created) || deadline.isAfter(departure)) state="REVIEW_REQUIRED";
        else if (!List.of("HELD","CHECKOUT").contains(hold)) state="REVIEW_REQUIRED";
        else if (!deadline.isAfter(now)) state="DEADLINE_PASSED";
        else state="CHECKOUT".equals(hold)?"CHECKOUT":"HELD";
        long remaining=List.of("HELD","CHECKOUT").contains(state)?Math.max(0,Duration.between(now,deadline).getSeconds()):0;
        return new Item(number(r,"id"),number(r,"tour_id"),(String)r.get("tour_program"),(String)r.get("tour_title"),
                created,departure,state,hold,deadline,remaining,now,RULE_VERSION);
    }
    private static int number(Map<String,Object> row,String key) { return ((Number)row.get(key)).intValue(); }
    private static BigDecimal amount(Object value) { return value==null?null:new BigDecimal(value.toString()); }
    private static OffsetDateTime date(Object value) {
        if(value==null) return null;
        LocalDateTime time=value instanceof LocalDateTime t?t:((java.sql.Timestamp)value).toLocalDateTime();
        return time.atZone(ZONE).toOffsetDateTime();
    }
}
