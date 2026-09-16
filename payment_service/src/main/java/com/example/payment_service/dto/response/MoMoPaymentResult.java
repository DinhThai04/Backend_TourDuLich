package com.example.payment_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class MoMoPaymentResult {
    private String payUrl;
    private String qrCodeUrl;
    private String qrCodeBase64; // data URI like data:image/png;base64,....
    private String deeplink;

    private MoMoResponse moMoResponse;
}
