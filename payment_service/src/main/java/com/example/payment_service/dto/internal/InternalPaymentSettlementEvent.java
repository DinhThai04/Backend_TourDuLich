package com.example.payment_service.dto.internal;

import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InternalPaymentSettlementEvent {
    private String eventId;
    private String eventType; // "payment.settled.v1" | "payment.failed.v1" | "payment.refunded.v1"
    private LocalDateTime occurredAt;
    private Integer bookingId;
    private Integer accountId;
    private Long amountVnd;
    private Integer payMethodId; // 1=vnpay, 2=momo
    private String providerRef; // vnp_TxnRef or MoMo orderId
    private String providerTxnNo; // vnp_TransactionNo or MoMo transId
    private LocalDateTime paidAt;
    private String status; // PAID, FAILED, REFUNDED
}