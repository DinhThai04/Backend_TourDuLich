package com.example.tour_service.service;

import com.example.tour_service.entity.TourDeparture;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

/** Read projection for the current shared DB. Caller locks departure before reading. Never repairs data. */
@Component
public class DepartureCapacityReader {
    /** The one issue a confirmed capacity repair is allowed to resolve. */
    public static final String CAPACITY_MISMATCH =
            "Tổng sức chứa không khớp chỗ đã chiếm và chỗ còn lại; cần đối chiếu dữ liệu";
    private final JdbcTemplate jdbc;
    public DepartureCapacityReader(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record State(int paidSeats,int heldSeats,int overdueHolds,int historyCount,boolean relatedHistory,List<String> issues,List<String> warnings) {
        public int occupied() { return Math.addExact(paidSeats,heldSeats); }
    }
    public State read(TourDeparture departure,LocalDateTime now) {
        var rows=jdbc.queryForList("""
            SELECT b.id,b.status,b.tour_departure_id,
              r.booking_id AS reservation_id,r.departure_id,r.departure_at,r.seats,r.status AS reservation_status,r.expires_at,
              (SELECT COUNT(*) FROM customer c WHERE c.booking_id=b.id) AS people,
              (SELECT COUNT(*) FROM invoice i WHERE i.booking_id=b.id AND i.status='PAID')
                +(SELECT COUNT(*) FROM payment p WHERE p.booking_id=b.id AND p.status='PAID')
                +(SELECT COUNT(*) FROM vnpay_transaction v WHERE v.booking_id=b.id AND v.status='PAID') AS paid_evidence,
              (SELECT COUNT(*) FROM booking_change c WHERE c.booking_id=b.id AND c.status='REFUNDED' AND c.refund_amount IS NOT NULL AND c.refund_reference IS NOT NULL) AS refund_evidence,
              (SELECT COUNT(*) FROM payment_reconciliation x WHERE x.booking_id=b.id AND x.status='OPEN') AS reconciliation
              ,(SELECT COUNT(*) FROM vnpay_transaction v WHERE v.booking_id=b.id AND v.status='PENDING') AS pending_checkout
            FROM booking b LEFT JOIN booking_reservation r ON r.booking_id=b.id
            WHERE b.tour_departure_id=? OR r.departure_id=? ORDER BY b.id
            """,departure.getId(),departure.getId());
        int paid=0,held=0,overdue=0;
        var issues=new LinkedHashSet<String>();var warnings=new LinkedHashSet<String>();
        for(var row:rows) {
            int people=number(row,"people"),id=number(row,"id");boolean evidence=number(row,"paid_evidence")>0 && number(row,"refund_evidence")==0;
            boolean live=List.of("PENDING_PAYMENT","CONFIRMED").contains(String.valueOf(row.get("status")));
            if(number(row,"reconciliation")>0) issues.add("Có giao dịch cần đối soát; cần xử lý trước khi thay đổi sức chứa");
            if(evidence && !live) issues.add("Booking #"+id+" đã hủy nhưng còn chứng từ PAID");
            if(row.get("reservation_id")==null) {
                if(number(row,"pending_checkout")>0 && !evidence)
                    issues.add("Có checkout VNPay cũ chưa giữ chỗ và chưa chốt kết quả; cần đối chiếu trước khi sửa sức chứa (hết hạn link chưa có nghĩa là chưa thu tiền)");
                if(evidence) {
                    paid=Math.addExact(paid,people);
                    if(people<=0)issues.add("Booking #"+id+" có tiền nhưng thiếu hành khách");
                    warnings.add("Có booking cũ đã thanh toán chưa có reservation; cần đối chiếu tổng sức chứa thực tế");
                } else if(live) {
                    // CONFIRMED means money was taken in the current lifecycle. A missing document
                    // trail must not release those seats, otherwise a capacity repair oversells.
                    held=Math.addExact(held,Math.max(0,people));
                    warnings.add("Có booking cũ đã xác nhận nhưng thiếu chứng từ thanh toán; vẫn tính là đang giữ chỗ để tránh bán lại");
                }
                continue;
            }
            int seats=number(row,"seats");String status=String.valueOf(row.get("reservation_status"));
            if(number(row,"departure_id")!=departure.getId() || number(row,"tour_departure_id")!=departure.getId()
                    || !departure.getDepartureDate().equals(date(row.get("departure_at"))))
                issues.add("Booking #"+id+" có snapshot lịch không khớp");
            if(seats<=0 || seats!=people) issues.add("Booking #"+id+" có số khách không khớp số chỗ");
            if("PAID".equals(status)) {
                paid=Math.addExact(paid,Math.max(0,seats));
                if(!evidence) issues.add("Booking #"+id+" PAID nhưng thiếu chứng từ");
            } else if(List.of("HELD","CHECKOUT").contains(status)) {
                held=Math.addExact(held,Math.max(0,seats));
                if(!live || evidence) issues.add("Booking #"+id+" có trạng thái giữ chỗ mâu thuẫn");
                if(!date(row.get("expires_at")).isAfter(now))overdue++;
            } else {
                if(evidence) issues.add("Booking #"+id+" đã trả chỗ nhưng còn chứng từ PAID");
                if(!List.of("EXPIRED","CANCELLED").contains(status) || live)
                    issues.add("Booking #"+id+" có trạng thái đóng giữ chỗ cần đối chiếu");
            }
        }
        int reservations=jdbc.queryForObject("SELECT COUNT(*) FROM booking_reservation WHERE departure_id=?",Integer.class,departure.getId());
        if(overdue>0 && "OPEN".equals(departure.getOperationalStatus()))issues.add("Còn giữ chỗ quá hạn chưa xử lý; cần chạy Booking service rồi tải lại");
        if(departure.getAvailableSeats()==null || departure.getAvailableSeats()<0)issues.add("Số chỗ còn lại không hợp lệ");
        if(departure.getCapacity()!=null && (departure.getCapacity()<0 || departure.getCapacity()>32767 || departure.getAvailableSeats()==null || departure.getCapacity()!=paid+held+departure.getAvailableSeats()))
            issues.add(CAPACITY_MISMATCH);
        boolean history=!rows.isEmpty() || reservations>0;
        if(!history)history=jdbc.queryForObject("""
            SELECT (SELECT COUNT(*) FROM booking_change WHERE source_departure_id=? OR target_departure_id=?)
                +(SELECT COUNT(*) FROM departure_change WHERE source_departure_id=? OR target_departure_id=?)
            """,Integer.class,departure.getId(),departure.getId(),departure.getId(),departure.getId())>0;
        return new State(paid,held,overdue,rows.size(),history,List.copyOf(issues),List.copyOf(warnings));
    }
    private static int number(Map<String,Object> row,String name) { return ((Number)row.get(name)).intValue(); }
    private static LocalDateTime date(Object value) { return value instanceof LocalDateTime time?time:((java.sql.Timestamp)value).toLocalDateTime(); }
}
