package com.example.invoice_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VnPayResponse {
    private String status;
    private String orderId;
    private String amount;
    private String bankCode;
    private String transactionNo;
    private String bankTranNo;
    private String cardType;
    private String orderInfo;
    private String payDate;
    private String responseCode;
    private String transactionStatus;
    private String txnRef;
    private String secureHash;
}
