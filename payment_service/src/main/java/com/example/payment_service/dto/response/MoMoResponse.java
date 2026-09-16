package com.example.payment_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class MoMoResponse {
    private String partnerCode;
    private String orderId;
    private String requestId;
    private Long amount;
    private String orderInfo;
    private String orderType;
    private String transId;
    private String message;
    private String localMessage;
    private String responseTime;
    private String errorCode;
    private String payUrl;
    private String deeplink;
    private String qrCodeUrl;
    private String deeplinkMiniApp;
}