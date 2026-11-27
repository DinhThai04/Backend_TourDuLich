package com.example.booking_service.controller;

import com.example.booking_service.service.InternalBookingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/internal/booking")
@RequiredArgsConstructor
public class InternalBookingController {
    private final InternalBookingService bookings;

    public record DecrementSeatsRequest(Integer seats) {}

    @GetMapping("/users/{userId}/active-bookings")
    public ResponseEntity<List<Map<String, Object>>> getActiveBookings(@PathVariable Long userId) {
        return bookings.getActiveBookings(userId);
    }

    @GetMapping("/users/{userId}/pending-changes")
    public ResponseEntity<List<Map<String, Object>>> getPendingChanges(@PathVariable Long userId) {
        return bookings.getPendingChanges(userId);
    }

    @GetMapping("/users/{userId}/has-history")
    public ResponseEntity<Map<String, Object>> hasUserHistory(@PathVariable Long userId) {
        return bookings.hasUserHistory(userId);
    }

    @GetMapping("/bookings/{id}")
    public ResponseEntity<Map<String, Object>> getBooking(@PathVariable Integer id) {
        return bookings.getBooking(id);
    }

    @GetMapping("/bookings/{id}/owner")
    public ResponseEntity<Map<String, Object>> getBookingOwner(@PathVariable Integer id) {
        return bookings.getBookingOwner(id);
    }

    @GetMapping("/bookings/{id}/reservation")
    public ResponseEntity<Map<String, Object>> getBookingReservation(@PathVariable Integer id) {
        return bookings.getBookingReservation(id);
    }

    @GetMapping("/bookings/{id}/customers")
    public ResponseEntity<List<Map<String, Object>>> getBookingCustomers(@PathVariable Integer id) {
        return bookings.getBookingCustomers(id);
    }

    @GetMapping("/bookings/{id}/price-snapshot")
    public ResponseEntity<Map<String, Object>> getBookingPriceSnapshot(@PathVariable Integer id) {
        return bookings.getBookingPriceSnapshot(id);
    }

    @GetMapping("/bookings/{id}/payment-evidence")
    public ResponseEntity<Map<String, Object>> getPaymentEvidence(@PathVariable Integer id) {
        return bookings.getPaymentEvidence(id);
    }

    @PostMapping("/bookings/{id}/decrement-seats")
    public ResponseEntity<Void> decrementSeats(@PathVariable Integer id, @RequestBody DecrementSeatsRequest request) {
        return bookings.decrementSeats(id, request.seats());
    }

    @PostMapping("/bookings/{id}/mark-paid")
    public ResponseEntity<Void> markBookingPaid(@PathVariable Integer id) {
        return bookings.markBookingPaid(id);
    }

    @PostMapping("/bookings/{id}/expire-checkout")
    public ResponseEntity<Void> expireCheckout(@PathVariable Integer id) {
        return bookings.expireCheckout(id);
    }
}
