package com.example.booking_service.dto.request;

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
    @NotBlank(message = "Thiếu tài khoản đặt tour")
    private String userId;
    @NotBlank(message = "Thiếu lịch khởi hành")
    private String tourDepartureId;
    @NotEmpty(message = "Cần ít nhất một hành khách")
    @Valid
    private List<CustomerRequest> listOfCustomers;
    /** UUID generated once per checkout attempt; a retry reuses it instead of creating another booking. */
    private String clientKey;
    /** Vietnam calendar date used for the preview. Older clients may omit it. */
    private java.time.LocalDate pricingDate;
    @jakarta.validation.constraints.Pattern(regexp = "[0-9]{1,18}", message = "Tổng dự kiến không hợp lệ")
    private String expectedAmount;
    @jakarta.validation.constraints.Pattern(regexp = "[a-f0-9]{64}", message = "Xác nhận điểm tập trung không hợp lệ")
    private String pickupConfirmationKey;
}
