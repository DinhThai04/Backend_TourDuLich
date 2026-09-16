package com.example.booking_service.client;

import com.example.booking_service.config.FeignAuthConfig;
import com.example.booking_service.dto.response.ApiResponse;
import com.example.booking_service.dto.response.TourDepartureResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(
        name = "tour-departure-service",
        url = "http://localhost:8888/api/v1/tour",
        configuration = FeignAuthConfig.class
)
public interface TourDepartureClient {
    @GetMapping("/tour-departures/{id}")
    ApiResponse<TourDepartureResponse> getTourDepartureById(@PathVariable("id") Long id);

}
