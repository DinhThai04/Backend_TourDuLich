package com.example.auth_service.repository.httpclient;

import com.example.auth_service.config.BookingFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;
import java.util.Map;

@FeignClient(name = "booking-service-internal", url = "${app.services.booking-base-url:http://localhost:8082/booking}", configuration = BookingFeignConfiguration.class)
public interface BookingInternalClient {

    @GetMapping("/internal/booking/users/{userId}/active-bookings")
    List<Map<String, Object>> getActiveBookings(@PathVariable("userId") Long userId);

    @GetMapping("/internal/booking/users/{userId}/pending-changes")
    List<Map<String, Object>> getPendingChanges(@PathVariable("userId") Long userId);

    @GetMapping("/internal/booking/users/{userId}/has-history")
    Map<String, Object> hasUserHistory(@PathVariable("userId") Long userId);
}
