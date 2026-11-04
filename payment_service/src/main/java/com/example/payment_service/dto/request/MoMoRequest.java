package com.example.payment_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class MoMoRequest {
    @NotNull(message = "Mã booking không được để trống")
    private Integer bookingId;
    @NotBlank(message = "Số tiền thanh toán không được để trống")
    private String expectedAmount;
}
