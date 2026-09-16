package com.example.tour_service.controller;

import com.example.tour_service.dto.request.ApiResponse;
import com.example.tour_service.dto.request.TourRequest;
import com.example.tour_service.dto.request.VehicleRequest;
import com.example.tour_service.dto.response.TourResponse;
import com.example.tour_service.dto.response.VehicleResponse;
import com.example.tour_service.service.VehicleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/vehicles")
@RequiredArgsConstructor
public class VehicleController {
    private final VehicleService vehicleService;

    @PostMapping
    public ApiResponse<VehicleResponse> createVehicle(@RequestBody VehicleRequest request) {
        return ApiResponse.<VehicleResponse>builder()
                .result(vehicleService.createVehicle(request))
                .message("Created Successfully")
                .build();
    }

    @GetMapping("/list")
    public ApiResponse<List<VehicleResponse>> getAllVehicles() {
        return ApiResponse.<List<VehicleResponse>>builder().
                result(vehicleService.getAllVehicles()).message("Get all vehicles successfully").build();
    }
}
