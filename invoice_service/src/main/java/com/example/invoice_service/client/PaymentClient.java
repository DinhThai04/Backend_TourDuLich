package com.example.invoice_service.client;

import com.example.invoice_service.config.FeignAuthConfig;
import com.example.invoice_service.dto.request.MoMoRequest;
import com.example.invoice_service.dto.request.PaymentRequest;
import com.example.invoice_service.dto.response.ApiResponse;
import com.example.invoice_service.dto.response.MoMoResponse;
import com.example.invoice_service.dto.response.TourPriceResponse;
import com.example.invoice_service.dto.response.VnPayResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@FeignClient(
        name = "payment-service",
        url = "http://localhost:8888/api/v1/payment",
        configuration = FeignAuthConfig.class
)

public interface PaymentClient {

    @PostMapping("/momo/create")
    ApiResponse<MoMoResponse> createMoMoPayment(@RequestBody MoMoRequest request);

    @PostMapping("/vnpay")
    ApiResponse<ResponseEntity<String>> createVnpayPayment(@RequestBody PaymentRequest request);

    @GetMapping("/vnpay/return")
    ApiResponse<VnPayResponse> returnPayment(@RequestParam Map<String, String> allRequestParams, HttpServletRequest request);
}