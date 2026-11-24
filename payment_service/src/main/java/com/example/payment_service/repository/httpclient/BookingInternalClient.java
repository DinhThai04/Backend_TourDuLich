package com.example.payment_service.repository.httpclient;

import com.example.payment_service.config.BookingFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@FeignClient(name = "booking-service-internal", url = "${app.services.booking-base-url:http://localhost:8082/booking}", configuration = BookingFeignConfiguration.class)
public interface BookingInternalClient {

    @GetMapping("/internal/booking/bookings/{id}")
    Map<String, Object> getBooking(@PathVariable("id") Integer id);

    @GetMapping("/internal/booking/bookings/{id}/reservation")
    Map<String, Object> getBookingReservation(@PathVariable("id") Integer id);

    @GetMapping("/internal/booking/bookings/{id}/customers")
    List<Map<String, Object>> getBookingCustomers(@PathVariable("id") Integer id);

    @GetMapping("/internal/booking/bookings/{id}/price-snapshot")
    Map<String, Object> getBookingPriceSnapshot(@PathVariable("id") Integer id);

    @PostMapping("/internal/booking/bookings/{id}/mark-paid")
    void markBookingPaid(@PathVariable("id") Integer id);

    @PostMapping("/internal/booking/bookings/{id}/expire-checkout")
    void expireCheckout(@PathVariable("id") Integer id);
}
