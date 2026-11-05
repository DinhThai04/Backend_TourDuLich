package com.example.invoice_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Entity
@Table(name = "invoice")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Invoice {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Column(name = "account_id", nullable = false)
    private int accountId;

    @Column(name = "booking_id", nullable = false)
    private int bookingId;

    @Column(name = "day_of_pay", nullable = false)
    private LocalDateTime dayOfPay;

    @Column(name = "pay_method_id", nullable = false)
    private int paymentMethodId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private InvoiceStatus status;

    @Column(name = "total_booking_tour_expense", nullable = false, precision = 19, scale = 0)
    private BigDecimal totalBookingTourExpense;

    @PrePersist
    protected void onStatus() {
        if (this.dayOfPay == null) this.dayOfPay = LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        if (this.status == null) this.status = InvoiceStatus.PAID;
    }
}