package com.example.payment_service.repository;

import com.example.payment_service.entity.PaymentReconciliation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository thao tác với bảng payment_reconciliation. Dùng để lưu và truy vấn các giao dịch
 * cần đối soát giữa hệ thống nội bộ và nhà cung cấp thanh toán bên ngoài.
 */
@Repository
public interface PaymentReconciliationRepository extends JpaRepository<PaymentReconciliation, String> {

    boolean existsByTxnRefAndStatus(String txnRef, String status);
}
