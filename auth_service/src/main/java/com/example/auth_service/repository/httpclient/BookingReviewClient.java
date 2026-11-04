package com.example.auth_service.repository.httpclient;

import com.example.auth_service.dto.request.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name="booking-review",url="${app.services.booking-base-url:http://localhost:8081/booking}")
public interface BookingReviewClient {
    record Eligibility(boolean eligible,String message) {}
    @GetMapping("/advice/review-eligibility")
    ApiResponse<Eligibility> check(@RequestParam("tourId") long tourId,@RequestHeader("Authorization") String authorization);
}
