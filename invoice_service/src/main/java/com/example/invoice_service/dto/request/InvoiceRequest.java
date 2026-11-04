package com.example.invoice_service.dto.request;

import com.example.invoice_service.entity.InvoiceStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceRequest {
    @NotNull(message = "Thiếu thông tin booking")
    @Valid
    private BookingRequest bookingRequest;
}
