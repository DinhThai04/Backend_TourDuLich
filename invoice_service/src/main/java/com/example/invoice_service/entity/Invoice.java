package com.example.invoice_service.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.context.annotation.Primary;

import java.time.LocalDateTime;

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
    private int bookingId;;

    @Column(name = "day_of_pay", nullable = false)
    private LocalDateTime dayOfPay;

    @Column(name = "pay_method_id", nullable = false)
    private int paymentMethodId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private InvoiceStatus status;

    @Column(name = "total_booking_tour_expense", nullable = false)
    private float totalBookingTourExpense;

    @PrePersist
    protected void onStatus() {
        this.dayOfPay = LocalDateTime.now();
        this.status = InvoiceStatus.PAID;

    }
}