package com.example.invoice_service.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingRequest {
    @NotBlank(message = "Thiếu mã booking")
    private String bookingId;
    @NotBlank(message = "Thiếu mã tài khoản")
    private String userId;
    @NotBlank(message = "Thiếu mã lịch khởi hành")
    private String tourDepartureId;
    @NotEmpty(message = "Booking chưa có hành khách")
    @Valid
    private List<CustomerRequest> listOfCustomers;
}
