package com.example.payment_service.controller;

import com.example.payment_service.dto.request.ApiResponse;
import com.example.payment_service.dto.request.VnPayRequest;
import com.example.payment_service.dto.response.VnPayResponse;
import com.example.payment_service.service.VnPayService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.Map;


@RestController
@RequiredArgsConstructor
@RequestMapping("/vnpay")
public class VnPayController {
    private final VnPayService vnPayService;

    @PostMapping
    public ResponseEntity<String> createPayment(@RequestBody VnPayRequest paymentRequest) {
        try {
            String paymentUrl = vnPayService.createPayment(paymentRequest);
            return ResponseEntity.ok(paymentUrl);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Đã xảy ra lỗi khi tạo thanh toán!");
        }
    }

    @GetMapping("/return")
    public ApiResponse<VnPayResponse> returnPayment(@RequestParam Map<String, String> allRequestParams,
                                                    HttpServletRequest request) {
        return ApiResponse.<VnPayResponse>builder()
                .result(vnPayService.handlePaymentReturn(allRequestParams, request))
                .message("Got successfully")
                .build();
    }

}
