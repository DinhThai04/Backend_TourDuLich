package com.example.pricing_service.controller;

import com.example.pricing_service.dto.request.ApiResponse;
import com.example.pricing_service.dto.request.TourPriceBatchRequest;
import com.example.pricing_service.dto.request.TourPriceRequest;
import com.example.pricing_service.dto.response.TourPriceResponse;
import com.example.pricing_service.service.TourPriceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/prices")
@RequiredArgsConstructor
public class TourPriceController {

    private final TourPriceService service;

    @PostMapping
    public ApiResponse<TourPriceResponse> create(@RequestBody TourPriceRequest request) {
        return ApiResponse.<TourPriceResponse>builder()
                .result(service.createPrice(request))
                .message("Created successfully")
                .build();
    }

    @PutMapping("/{id}")
    public ApiResponse<TourPriceResponse> update(@PathVariable Long id, @RequestBody TourPriceRequest request) {
        return ApiResponse.<TourPriceResponse>builder()
                .result(service.updatePrice(id, request))
                .message("Updated successfully")
                .build();
    }

//    @DeleteMapping("/{id}")
//    public ApiResponse<Void> delete(@PathVariable Long id) {
//        service.deletePrice(id);
//        return ApiResponse.<Void>builder()
//                .message("Deleted successfully")
//                .build();
//    }

    @GetMapping
    public ApiResponse<List<TourPriceResponse>> getAll() {
        return ApiResponse.<List<TourPriceResponse>>builder()
                .result(service.getAllPrices())
                .message("Fetched successfully")
                .build();
    }

    @GetMapping("/{id}")
    public ApiResponse<TourPriceResponse> getById(@PathVariable Long id) {
        return ApiResponse.<TourPriceResponse>builder()
                .result(service.getPriceById(id))
                .message("Fetched successfully")
                .build();
    }

    @PostMapping("/batch")
    public ApiResponse<List<TourPriceResponse>> getPricesBatch(@RequestBody TourPriceBatchRequest request) {
        List<TourPriceResponse> prices = service.getPricesBatch(request.getIds());
        return ApiResponse.<List<TourPriceResponse>>builder()
                .result(prices)
                .message("Fetched successfully")
                .build();
    }

}