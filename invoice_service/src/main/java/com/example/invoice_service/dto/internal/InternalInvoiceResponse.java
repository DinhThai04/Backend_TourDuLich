package com.example.invoice_service.dto.internal;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InternalInvoiceResponse {
    private Integer id;
    private Integer accountId;
    private Integer bookingId;
    private LocalDateTime dayOfPay;
    private Integer paymentMethodId;
    private String status;
    private BigDecimal totalBookingTourExpense;
}