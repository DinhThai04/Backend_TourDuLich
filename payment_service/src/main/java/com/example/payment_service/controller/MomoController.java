package com.example.payment_service.controller;

import com.example.payment_service.dto.request.ApiResponse;
import com.example.payment_service.dto.request.CreateMomoRequest;
import com.example.payment_service.service.MomoService;
import com.example.payment_service.dto.response.CreateMomoResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/momo")
public class MomoController {
    private final MomoService momoService;

    @PostMapping("/create")
    public ApiResponse<CreateMomoResponse> createQR(@RequestBody CreateMomoRequest request){
        try {
            // Gọi service để tạo QR
//            CreateMomoResponse response = momoService.createMomoQR(
//                    request.getOrderId(),
//                    request.getOrderInfo(),
//                    request.getRequestId(),
//                    request.getExtraData(),
//                    String.valueOf(request.getAmount())
//            );


            CreateMomoResponse response = momoService.createMomoQR(
                    request.getOrderId(),
                    request.getOrderInfo(),
                    request.getRequestId(),
                    request.getExtraData(),
                    request.getAmount());
            return ApiResponse.<CreateMomoResponse>builder()
                    .result(response)
                    .message("Tạo QR MoMo thành công")
                    .build();
        } catch (Exception e) {
            return ApiResponse.<CreateMomoResponse>builder()
                    .message("Lỗi khi tạo QR MoMo: " + e.getMessage())
                    .result(null)
                    .build();
        }
    }
}
