package com.example.tour_service.controller;

import com.example.tour_service.dto.internal.HoldSeatsRequest;
import com.example.tour_service.dto.internal.HoldSeatsResponse;
import com.example.tour_service.dto.internal.InternalDepartureCapacityResponse;
import com.example.tour_service.dto.internal.ReleaseHoldResponse;
import com.example.tour_service.service.InternalDepartureService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/internal/tour/departures")
@RequiredArgsConstructor
public class InternalTourDepartureController {
    private final InternalDepartureService departures;

    @GetMapping("/operations")
    public List<Map<String, Object>> reportDepartures() {
        return departures.reportDepartures();
    }

    @GetMapping("/{id}/capacity")
    public ResponseEntity<InternalDepartureCapacityResponse> getCapacity(@PathVariable Integer id) {
        return departures.getCapacity(id);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getDeparture(@PathVariable Integer id) {
        return departures.getDeparture(id);
    }

    @GetMapping("/{id}/itinerary")
    public List<Map<String, Object>> departureItinerary(@PathVariable int id) {
        return departures.departureItinerary(id);
    }

    @PostMapping("/{id}/hold")
    public ResponseEntity<HoldSeatsResponse> holdSeats(
            @PathVariable Integer id,
            @Valid @RequestBody HoldSeatsRequest request) {
        return departures.holdSeats(id, request);
    }

    @PutMapping("/{id}/hold/{bookingId}/confirm")
    public ResponseEntity<Void> confirmHold(@PathVariable Integer id, @PathVariable Integer bookingId) {
        return departures.confirmHold(id, bookingId);
    }

    @DeleteMapping("/{id}/hold")
    public ResponseEntity<ReleaseHoldResponse> releaseHold(
            @PathVariable Integer id,
            @RequestParam Integer seats,
            @RequestParam(required = false) Integer bookingId) {
        return departures.releaseHold(id, seats, bookingId);
    }

    @GetMapping("/users/{userId}/has-history")
    public ResponseEntity<Map<String, Object>> hasUserHistory(@PathVariable Long userId) {
        return departures.hasUserHistory(userId);
    }

    @GetMapping("/by-tour/{tourId}")
    public ResponseEntity<List<Map<String, Object>>> getDeparturesByTour(@PathVariable Integer tourId) {
        return departures.getDeparturesByTour(tourId);
    }

    @PostMapping("/{id}/operational-status")
    public ResponseEntity<Void> updateOperationalStatus(
            @PathVariable Integer id,
            @RequestParam String status) {
        return departures.updateOperationalStatus(id, status);
    }

    @PostMapping("/replacement")
    public ResponseEntity<Map<String, Object>> createReplacement(@RequestBody Map<String, Object> req) {
        return departures.createReplacement(req);
    }

    @PostMapping("/{id}/increment-version")
    public ResponseEntity<Void> incrementVersion(@PathVariable Integer id) {
        return departures.incrementVersion(id);
    }
}
