package com.example.invoice_service.client;

import com.example.invoice_service.config.FeignAuthConfig;
import com.example.invoice_service.dto.request.TourDepartureRequest;
import com.example.invoice_service.dto.response.ApiResponse;
import com.example.invoice_service.dto.response.TourDepartureResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(
        name = "tour-service",
        url = "http://localhost:8888/api/v1/tour",
        configuration = FeignAuthConfig.class
)
public interface TourDepartureClient {
    @GetMapping("/tour-departures/{id}")
    ApiResponse<TourDepartureResponse> getTourDepartureById(@PathVariable("id") Long id);

    @PutMapping("/tour-departures/{id}")
    ApiResponse<TourDepartureResponse> updateTourDepartureAvalableSeats(@PathVariable("id") Long id, @RequestBody TourDepartureRequest request);
}