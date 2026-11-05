package com.example.payment_service.repository;

import com.example.payment_service.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.Optional;

/**
 * Repository thao tác với bảng payment. Cung cấp truy vấn tìm thanh toán theo booking để xác
 * minh xem booking đã được thanh toán hay chưa.
 */
@Repository
public interface PaymentRepository extends JpaRepository<Payment, Integer> {

    /**
     * Kiểm tra xem booking đã có bản ghi thanh toán PAID hay chưa.
     *
     * @param bookingId mã booking
     * @return true nếu đã tồn tại thanh toán PAID
     */
    @Query("SELECT COUNT(p) > 0 FROM Payment p WHERE p.bookingId = ?1 AND p.status = 'PAID'")
    boolean existsPaidByBookingId(Integer bookingId);
}
