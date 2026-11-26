package com.example.booking_service.reservation;

import com.example.booking_service.repository.httpclient.TourInternalClient;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BookingSettlementService {
    private final JdbcTemplate jdbc;
    private final TourInternalClient tours;
    private final Clock clock;

    @Transactional
    public void confirmPaid(int bookingId) {
        var bookings = jdbc.queryForList("SELECT status,tour_departure_id FROM booking WHERE id=? FOR UPDATE", bookingId);
        if (bookings.isEmpty()) throw new IllegalStateException("Không tìm thấy đơn cần xác nhận");
        var booking = bookings.getFirst();
        if (!List.of("PENDING_PAYMENT", "CONFIRMED").contains(booking.get("status")))
            throw new IllegalStateException("Đơn đã đóng, cần đối chiếu khoản thu");
        var holds = jdbc.queryForList("SELECT departure_id,status FROM booking_reservation WHERE booking_id=? FOR UPDATE", bookingId);
        if (!holds.isEmpty()) {
            var hold = holds.getFirst();
            if (!List.of("HELD", "CHECKOUT", "PAID").contains(hold.get("status"))
                    || !booking.get("tour_departure_id").equals(hold.get("departure_id")))
                throw new IllegalStateException("Giữ chỗ của đơn không còn hợp lệ");
        }
        tours.confirmHold(((Number) booking.get("tour_departure_id")).intValue(), bookingId);
        jdbc.update("UPDATE booking SET status='CONFIRMED' WHERE id=? AND status='PENDING_PAYMENT'", bookingId);
        jdbc.update("""
                UPDATE booking_reservation SET status='PAID',updated_at=?
                WHERE booking_id=? AND status IN ('HELD','CHECKOUT')
                """, LocalDateTime.now(clock), bookingId);
    }
}
