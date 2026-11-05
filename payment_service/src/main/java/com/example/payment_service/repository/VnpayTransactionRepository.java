package com.example.payment_service.repository;

import com.example.payment_service.entity.VnpayTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.Optional;

/**
 * Repository thao tác với bảng vnpay_transaction. Cung cấp truy vấn tìm giao dịch theo mã
 * tham chiếu (txn_ref) và theo booking để kiểm tra trạng thái thanh toán.
 */
@Repository
public interface VnpayTransactionRepository extends JpaRepository<VnpayTransaction, String> {

    /**
     * Tìm giao dịch VNPay theo mã tham chiếu duy nhất.
     *
     * @param txnRef mã tham chiếu giao dịch VNPay
     * @return Optional chứa giao dịch nếu tìm thấy
     */
    Optional<VnpayTransaction> findByTxnRef(String txnRef);

    /**
     * Kiểm tra xem booking đã có giao dịch VNPay đang chờ xử lý hay không.
     *
     * @param bookingId mã booking
     * @return true nếu tồn tại giao dịch PENDING chưa hết hạn
     */
    @Query("SELECT COUNT(v) > 0 FROM VnpayTransaction v WHERE v.bookingId = ?1 AND v.status = 'PENDING' AND v.expiresAt > ?2")
    boolean hasPendingTransaction(Integer bookingId, java.time.LocalDateTime expiresAt);
}
