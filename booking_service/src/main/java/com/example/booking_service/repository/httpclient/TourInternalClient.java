package com.example.booking_service.repository.httpclient;

import com.example.booking_service.config.TourFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@FeignClient(name = "tour-service-internal", url = "${app.services.tour-base-url:http://localhost:8086/tour}", configuration = TourFeignConfiguration.class)
public interface TourInternalClient {

    @GetMapping("/internal/tour/departures/{id}/capacity")
    Map<String, Object> getCapacity(@PathVariable("id") Integer id);

    @GetMapping("/internal/tour/departures/{id}")
    Map<String, Object> getDeparture(@PathVariable("id") Integer id);

    @PostMapping("/internal/tour/departures/{id}/hold")
    Map<String, Object> holdSeats(@PathVariable("id") Integer id, @RequestBody HoldSeatsRequest request);

    @DeleteMapping("/internal/tour/departures/{id}/hold")
    Map<String, Object> releaseHold(@PathVariable("id") Integer id, @RequestParam Integer seats);

    @PostMapping("/internal/tour/departures/{id}/increment-version")
    void incrementVersion(@PathVariable("id") Integer id);

    record HoldSeatsRequest(
            Integer seats,
            String bookingId,
            LocalDateTime expiresAt
    ) {}
}