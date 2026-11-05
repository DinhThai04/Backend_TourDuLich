package com.example.booking_service.advice;

import com.example.booking_service.notification.ReminderStateReader;
import com.example.booking_service.reservation.BookingAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDateTime;

@Service
public class ReviewEligibilityService {
    public record Result(boolean eligible, String message) {}
    private final JdbcTemplate jdbc;
    private final BookingAccess access;
    private final Clock clock;
    public ReviewEligibilityService(JdbcTemplate jdbc, BookingAccess access, Clock clock) {
        this.jdbc=jdbc; this.access=access; this.clock=clock;
    }
    @Transactional(readOnly=true)
    public Result check(long tourId) {
        if(tourId<1) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"Mã tour không hợp lệ");
        // Always the authenticated owner, including admins. Never accept an accountId from the caller.
        var ids=jdbc.queryForList("""
            SELECT b.id FROM booking b JOIN tour_departure d ON d.id=b.tour_departure_id
            WHERE b.account_id=? AND d.tour_id=? AND b.status='CONFIRMED' AND d.return_date<=?
              AND NOT EXISTS (SELECT 1 FROM booking_change c WHERE c.booking_id=b.id AND c.active_key=1)
            """, Integer.class, access.accountId(),tourId,LocalDateTime.now(clock));
        var reader=new ReminderStateReader(jdbc);
        for(int id:ids) {
            var found=reader.read(id);
            if(found.isEmpty()) continue;
            var s=found.get();
            if(s.paymentCount()==1 && s.invoiceCount()==1 && (s.allPaidCount()==2 || s.allPaidCount()==3)
                && s.paymentAmount()!=null && s.invoiceAmount()!=null && s.paymentAmount().signum()>0
                && s.paymentAmount().compareTo(s.invoiceAmount())==0 && !s.reconciliation()
                && s.passengers()>0 && s.reservationMatches()
                && (s.reservationStatus()==null || "PAID".equals(s.reservationStatus())))
                return new Result(true,"Bạn có thể đánh giá tour đã hoàn tất; mỗi tài khoản một đánh giá cho mỗi tour.");
        }
        return new Result(false,"Chỉ tài khoản có booking đã thanh toán được xác minh và chuyến đi đã kết thúc mới được đánh giá tour này.");
    }
}
