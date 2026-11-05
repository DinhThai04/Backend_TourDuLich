package com.example.invoice_service.client;

import com.example.invoice_service.config.FeignAuthConfig;
import com.example.invoice_service.dto.response.ApiResponse;
import com.example.invoice_service.dto.response.TourDepartureResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(
        name = "tour-service",
        url = "${app.services.tour-base-url:http://localhost:8086/tour}",
        configuration = FeignAuthConfig.class
)
public interface TourDepartureClient {
    @GetMapping("/tour-departures/{id}")
    ApiResponse<TourDepartureResponse> getTourDepartureById(@PathVariable("id") Long id);
}