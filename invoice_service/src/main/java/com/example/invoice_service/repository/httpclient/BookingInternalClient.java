package com.example.invoice_service.repository.httpclient;

import com.example.invoice_service.config.BookingFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@FeignClient(name = "booking-service-internal", url = "${app.services.booking-base-url:http://localhost:8081/booking}", configuration = BookingFeignConfiguration.class)
public interface BookingInternalClient {

    @GetMapping("/internal/booking/bookings/{id}")
    Map<String, Object> getBooking(@PathVariable("id") Integer id);

    @GetMapping("/internal/booking/bookings/{id}/owner")
    Map<String, Object> getBookingOwner(@PathVariable("id") Integer id);

    @GetMapping("/internal/booking/bookings/{id}/reservation")
    Map<String, Object> getBookingReservation(@PathVariable("id") Integer id);

    @GetMapping("/internal/booking/bookings/{id}/customers")
    java.util.List<Map<String, Object>> getBookingCustomers(@PathVariable("id") Integer id);

    @GetMapping("/internal/booking/bookings/{id}/price-snapshot")
    Map<String, Object> getBookingPriceSnapshot(@PathVariable("id") Integer id);

    @GetMapping("/internal/booking/bookings/{id}/payment-evidence")
    Map<String, Object> getPaymentEvidence(@PathVariable("id") Integer id);

    @PostMapping("/internal/booking/bookings/{id}/decrement-seats")
    void decrementSeats(@PathVariable("id") Integer id, @RequestBody DecrementSeatsRequest request);

    record DecrementSeatsRequest(
            Integer seats
    ) {}
}