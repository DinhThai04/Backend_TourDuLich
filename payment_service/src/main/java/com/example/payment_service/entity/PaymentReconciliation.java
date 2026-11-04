package com.example.payment_service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Entity ánh xạ bảng payment_reconciliation. Lưu các giao dịch cần đối soát giữa hệ thống nội
 * bộ và nhà cung cấp thanh toán (VNPay, MoMo). TxnRef là khóa chính để tránh đối soát trùng lặp.
 */
@Entity
@Table(name = "payment_reconciliation")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentReconciliation {

    @Id
    @Column(name = "txn_ref", length = 32, nullable = false)
    private String txnRef;

    @Column(name = "booking_id", nullable = false)
    private Integer bookingId;

    @Column(name = "reason", length = 100, nullable = false)
    private String reason;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "provider_paid_at", nullable = false)
    private LocalDateTime providerPaidAt;

    @Column(name = "amount_vnd", nullable = false)
    private Long amountVnd;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
