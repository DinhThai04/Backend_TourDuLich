package com.example.payment_service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Entity ánh x̣anh bảng vnpay_transaction. Mỗi giao dịch VNPay được lưu lại với mã giao dịch
 * duy nhất (txn_ref), trạng thái và thời gian hết hạn để đối soát và chặn thanh toán trùng lặp.
 */
@Entity
@Table(name = "vnpay_transaction")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VnpayTransaction {

    @Id
    @Column(name = "txn_ref", length = 32, nullable = false)
    private String txnRef;

    @Column(name = "account_id", nullable = false)
    private Integer accountId;

    @Column(name = "amount_vnd", nullable = false)
    private Long amountVnd;

    @Column(name = "booking_id", nullable = false)
    private Integer bookingId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "status", length = 16, nullable = false)
    @Enumerated(EnumType.STRING)
    private VnpayStatus status;

    @Column(name = "transaction_no", length = 255)
    private String transactionNo;

    public enum VnpayStatus {
        PENDING, PAID, FAILED, REFUNDED, CLOSED_UNPAID
    }
}
