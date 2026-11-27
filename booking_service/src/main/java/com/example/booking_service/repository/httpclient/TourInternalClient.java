package com.example.booking_service.repository.httpclient;

import com.example.booking_service.config.TourFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@FeignClient(name = "tour-service-internal", url = "${app.services.tour-base-url:http://localhost:8086/tour}", configuration = TourFeignConfiguration.class)
public interface TourInternalClient {

    @GetMapping("/internal/tour/departures/operations")
    java.util.List<Map<String, Object>> reportDepartures();

    @GetMapping("/internal/tour/departures/{id}/capacity")
    Map<String, Object> getCapacity(@PathVariable("id") Integer id);

    @GetMapping("/internal/tour/departures/{id}")
    Map<String, Object> getDeparture(@PathVariable("id") Integer id);

    @GetMapping("/tours/{id}")
    Map<String, Object> getTourForMail(@PathVariable("id") Integer id);

    @GetMapping("/internal/tour/departures/{id}/itinerary")
    java.util.List<Map<String, Object>> getItinerary(@PathVariable("id") int id);

    @PostMapping("/internal/tour/departures/{id}/hold")
    Map<String, Object> holdSeats(@PathVariable("id") Integer id, @RequestBody HoldSeatsRequest request);

    @DeleteMapping("/internal/tour/departures/{id}/hold")
    Map<String, Object> releaseHold(@PathVariable("id") Integer id, @RequestParam Integer seats);

    @DeleteMapping("/internal/tour/departures/{id}/hold")
    Map<String, Object> releaseHold(@PathVariable("id") Integer id, @RequestParam Integer seats, @RequestParam Integer bookingId);

    @PutMapping("/internal/tour/departures/{id}/hold/{bookingId}/confirm")
    void confirmHold(@PathVariable("id") Integer id, @PathVariable("bookingId") Integer bookingId);

    @PostMapping("/internal/tour/departures/{id}/increment-version")
    void incrementVersion(@PathVariable("id") Integer id);

    @GetMapping("/internal/tour/departures/by-tour/{tourId}")
    java.util.List<Map<String, Object>> getDeparturesByTour(@PathVariable("tourId") Integer tourId);

    @PostMapping("/internal/tour/departures/{id}/operational-status")
    void updateOperationalStatus(@PathVariable("id") Integer id, @RequestParam("status") String status);

    @PostMapping("/internal/tour/departures/replacement")
    Map<String, Object> createReplacement(@RequestBody Map<String, Object> request);

    record HoldSeatsRequest(
            Integer seats,
            String bookingId,
            LocalDateTime expiresAt,
            String pickupConfirmationKey
    ) {
        public HoldSeatsRequest(Integer seats, String bookingId, LocalDateTime expiresAt) {
            this(seats, bookingId, expiresAt, null);
        }
    }
}