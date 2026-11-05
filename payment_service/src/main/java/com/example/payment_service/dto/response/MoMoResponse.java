package com.example.payment_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MoMoResponse {
    private String status;
    private boolean alreadyProcessed;
    private String orderId;
    private String requestId;
    private String amount;
    private String transId;
    private String resultCode;
    private String message;
}
