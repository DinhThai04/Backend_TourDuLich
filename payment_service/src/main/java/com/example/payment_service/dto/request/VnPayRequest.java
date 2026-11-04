package com.example.payment_service.dto.request;

import lombok.Data;

@Data
public class VnPayRequest {
    private Integer bookingId;
    private String expectedAmount;
    private String amount; // Legacy endpoint only; booking endpoint ignores client amounts.
}
