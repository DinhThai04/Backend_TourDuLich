package com.example.invoice_service.dto.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PaymentSettlementEvent {
    private String eventId;
    private String eventType; // "payment.settled.v1" | "payment.failed.v1" | "payment.refunded.v1"
    private LocalDateTime occurredAt;
    private Integer bookingId;
    private Integer accountId;
    private Long amountVnd;
    private Integer payMethodId; // 1=vnpay, 2=momo, 3=cash
    private String providerRef;
    private String providerTxnNo;
    private LocalDateTime paidAt;
    private String status; // PAID, FAILED, REFUNDED
}