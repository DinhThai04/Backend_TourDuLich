package com.example.invoice_service.dto.response;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class MoMoResponse {
    private Integer resultCode; // 0 = success
    private String message;
    private String payUrl; // URL để mở QR/checkout
    private String requestId;
    private String orderId;
    private String orderInfo;
    private String extraData;
    private long amount;
    private String signature;
    private String transId;
}