package com.example.payment_service.controller;

import com.example.payment_service.dto.request.ApiResponse;
import com.example.payment_service.dto.request.VnPayRequest;
import com.example.payment_service.dto.response.BookingQuoteResponse;
import com.example.payment_service.dto.response.VnPayResponse;
import com.example.payment_service.service.VnPayService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;


@lombok.extern.slf4j.Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/vnpay")
public class VnPayController {
    private final VnPayService vnPayService;

    @PostMapping("/bookings")
    public ResponseEntity<String> createPayment(@RequestBody VnPayRequest paymentRequest, @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        try {
            String paymentUrl = vnPayService.createPayment(paymentRequest, Integer.parseInt(jwt.getSubject()), request);
            return ResponseEntity.ok(paymentUrl);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode()).body(e.getReason());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to prepare VNPay booking {}", paymentRequest.getBookingId(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Đã xảy ra lỗi khi tạo thanh toán!");
        }
    }

    @GetMapping("/bookings/{bookingId}/quote")
    public ApiResponse<BookingQuoteResponse> quote(@PathVariable int bookingId, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.<BookingQuoteResponse>builder()
                .result(vnPayService.quote(bookingId, Integer.parseInt(jwt.getSubject())))
                .message("Got successfully")
                .build();
    }

    @GetMapping("/bookings/return")
    public ApiResponse<VnPayResponse> returnPayment(@RequestParam Map<String, String> allRequestParams,
                                                    HttpServletRequest request) {
        return ApiResponse.<VnPayResponse>builder()
                .result(vnPayService.handlePaymentReturn(allRequestParams))
                .message("Got successfully")
                .build();
    }

    // VNPay calls this endpoint without a user token. The signed parameters are verified first.
    @GetMapping("/ipn")
    public Map<String, String> ipn(@RequestParam Map<String, String> fields) {
        try {
            var result = vnPayService.handlePaymentReturn(fields);
            if ("reconciliation".equals(result.getStatus()))
                return Map.of("RspCode", "00", "Message", "Received for reconciliation");
            return result.isAlreadyProcessed()
                ? Map.of("RspCode", "02", "Message", "Order already confirmed")
                : Map.of("RspCode", "00", "Message", "Confirm Success");
        } catch (com.example.payment_service.service.VnPayRejection rejection) {
            return Map.of("RspCode", rejection.rspCode(), "Message", rejection.getMessage());
        } catch (IllegalArgumentException ex) {
            return Map.of("RspCode", "99", "Message", "Invalid transaction");
        } catch (RuntimeException ex) {
            return Map.of("RspCode", "99", "Message", "Retry later");
        }
    }
}
