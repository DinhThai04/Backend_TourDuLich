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
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Entity ánh xạ bảng momo_transaction. Lưu thông tin giao dịch MoMo với mã đơn hàng (order_id)
 * và mã yêu cầu (request_id) để duy nhất hóa và chặn gọi trùng.
 */
@Entity
@Table(name = "momo_transaction")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MoMoTransaction {

    @Id
    @Column(name = "order_id", length = 32, nullable = false)
    private String orderId;

    @Column(name = "request_id", length = 32, nullable = false, unique = true)
    private String requestId;

    @Column(name = "booking_id", nullable = false)
    private Integer bookingId;

    @Column(name = "account_id", nullable = false)
    private Integer accountId;

    @Column(name = "amount_vnd", nullable = false)
    private Long amountVnd;

    @Column(name = "status", length = 16, nullable = false)
    @Enumerated(EnumType.STRING)
    private MoMoStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "trans_id")
    private Long transId;

    @Column(name = "result_code")
    private Integer resultCode;

    public enum MoMoStatus {
        PENDING, PAID, FAILED, REFUNDED
    }
}
