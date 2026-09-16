package com.example.payment_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO nhận response từ MoMo /v2/gateway/api/create
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateMomoResponse {
    private Integer resultCode; // 0 = success
    private String message;
    private String payUrl; // URL để mở QR/checkout
    private long amount;
    private String requestId;
    private String orderId;
    private String orderInfo;
    private String signature;
    private String transId;
    private String extraData;
}
