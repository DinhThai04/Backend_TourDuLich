package com.example.booking_service.repository.httpclient;

import com.example.booking_service.config.PaymentFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@FeignClient(name = "payment-service-internal", url = "${app.services.payment-base-url:http://localhost:8083/payment}", configuration = PaymentFeignConfiguration.class)
public interface PaymentInternalClient {

    @PostMapping("/internal/payment/refund")
    Map<String, Object> refund(@RequestParam Integer bookingId, @RequestParam Long amountVnd, @RequestParam String reference);

    @GetMapping("/internal/payment/booking/{bookingId}")
    Map<String, Object> getPaymentStatus(@PathVariable("bookingId") Integer bookingId);

    @GetMapping("/internal/payment/bookings/{bookingId}/evidence")
    Map<String, Object> getBookingPaymentEvidence(@PathVariable("bookingId") Integer bookingId);

    @GetMapping("/internal/payment/bookings/{bookingId}/settled-ref")
    Map<String, Object> hasSettledReference(
            @PathVariable("bookingId") Integer bookingId,
            @RequestParam("accountId") Long accountId,
            @RequestParam("transactionRef") String transactionRef,
            @RequestParam("amountVnd") Long amountVnd);
}