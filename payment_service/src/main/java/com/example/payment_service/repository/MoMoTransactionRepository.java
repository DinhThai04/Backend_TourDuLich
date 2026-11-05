package com.example.payment_service.repository;

import com.example.payment_service.entity.MoMoTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.Optional;

/**
 * Repository thao tác với bảng momo_transaction. Cung cấp truy vấn tìm giao dịch MoMo theo mã
 * đơn hàng (order_id) và mã yêu cầu (request_id).
 */
@Repository
public interface MoMoTransactionRepository extends JpaRepository<MoMoTransaction, String> {

    Optional<MoMoTransaction> findByOrderId(String orderId);

    Optional<MoMoTransaction> findByRequestId(String requestId);

    @Query("SELECT COUNT(m) > 0 FROM MoMoTransaction m WHERE m.bookingId = ?1 AND m.status = 'PENDING' AND m.expiresAt > ?2")
    boolean hasPendingTransaction(Integer bookingId, java.time.LocalDateTime expiresAt);
}
