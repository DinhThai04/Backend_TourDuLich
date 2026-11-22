package com.example.payment_service.dto.internal;

import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InternalPaymentResponse {
    private Integer id;
    private Integer accountId;
    private Integer bookingId;
    private LocalDateTime dayOfPay;
    private Integer payMethodId;
    private String status;
    private java.math.BigDecimal totalBookingTourExpense;
}