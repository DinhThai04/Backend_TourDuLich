package com.example.invoice_service.client;

import com.example.invoice_service.config.FeignAuthConfig;
import com.example.invoice_service.dto.response.ApiResponse;
import com.example.invoice_service.dto.response.BookingResponse;
import com.example.invoice_service.dto.response.TourPriceResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;

@FeignClient(
        name = "booking-service",
        url = "http://localhost:8888/api/v1/booking",
        configuration = FeignAuthConfig.class
)
public interface BookingClient {
    @GetMapping("/book/{id}")
    ApiResponse<BookingResponse> getBookingById(@PathVariable("id") Long id);

    @PutMapping("/{id}/status")
    ApiResponse<BookingResponse> updateBookingStatus(@PathVariable("id") Long id);
}