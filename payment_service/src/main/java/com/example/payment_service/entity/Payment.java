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
 * Entity ánh xạ bảng payment. Lưu thanh toán nội bộ của hệ thống (khác với giao dịch VNPay/MoMo
 * bên ngoài), dùng để đối soát và xác minh booking đã được thanh toán hay chưa.
 */
@Entity
@Table(name = "payment")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Payment {

    @Id
    @Column(name = "id", nullable = false)
    @jakarta.persistence.GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "method")
    private Integer method;

    @Column(name = "account_id", nullable = false)
    private Integer accountId;

    @Column(name = "booking_id", nullable = false)
    private Integer bookingId;

    @Column(name = "day_of_pay", nullable = false)
    private LocalDateTime dayOfPay;

    @Column(name = "pay_method_id", nullable = false)
    private Integer payMethodId;

    @Column(name = "status", length = 16, nullable = false)
    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    @Column(name = "total_booking_tour_expense", nullable = false, precision = 19, scale = 0)
    private BigDecimal totalBookingTourExpense;

    public enum PaymentStatus {
        PENDING, UNPAID, PAID, REFUNDED
    }
}
