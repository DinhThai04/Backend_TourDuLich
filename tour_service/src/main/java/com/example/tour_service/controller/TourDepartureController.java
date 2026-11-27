package com.example.tour_service.controller;

import com.example.tour_service.dto.response.ApiResponse;
import com.example.tour_service.dto.request.TourDepartureRequest;

import com.example.tour_service.dto.response.TourDepartureResponse;
import com.example.tour_service.service.TourDepartureService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("tour-departures")
@RequiredArgsConstructor
public class TourDepartureController {
    private final TourDepartureService tourDepartureService;
    public record VehicleAssignment(@jakarta.validation.constraints.Size(min = 1, max = 255) String vehicleId) {}

    @GetMapping("/upcoming")
    public ApiResponse<TourDepartureService.UpcomingPage> upcoming(@RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate day) {
        return ApiResponse.<TourDepartureService.UpcomingPage>builder()
                .result(tourDepartureService.upcoming(page, day)).build();
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasAnyAuthority('SGU_SUPER_ADMIN','PERM:TOUR_DEPARTURES_UPDATE','PERM:SHOP_CHANGE_CREATE')")
    @GetMapping("/{id}/capacity")
    public ApiResponse<com.example.tour_service.dto.response.DepartureCapacityResponse> capacity(@PathVariable int id) {
        return ApiResponse.<com.example.tour_service.dto.response.DepartureCapacityResponse>builder().result(tourDepartureService.capacity(id)).build();
    }

    @GetMapping("/{id}")
    public ApiResponse<TourDepartureResponse> getTourDepartureById(@PathVariable int id) {
        return ApiResponse.<TourDepartureResponse>builder()
                .result(tourDepartureService.getTourDepartureById(id))
                .message("Got Successfully")
                .build();
    }

    @GetMapping("/list")
    public ApiResponse<List<TourDepartureResponse>> getAll() {
        return ApiResponse.<List<TourDepartureResponse>>builder()
                .result(tourDepartureService.getAllTourDeparture())
                .message("Fetched successfully")
                .build();
    }

    @GetMapping("/tour/{tourId}")
    public ApiResponse<List<TourDepartureResponse>> getByTourId(@PathVariable int tourId) {
        return ApiResponse.<List<TourDepartureResponse>>builder()
                .result(tourDepartureService.getTourDepartureByTourId(tourId))
                .message("Fetched successfully")
                .build();
    }

    @GetMapping("/tour/{tourId}/all")
    public ApiResponse<List<TourDepartureResponse>> getHistoryByTourId(@PathVariable int tourId) {
        return ApiResponse.<List<TourDepartureResponse>>builder()
                .result(tourDepartureService.getTourDepartureHistory(tourId))
                .message("Fetched successfully")
                .build();
    }

    @PostMapping
    public ApiResponse<TourDepartureResponse> createTourDeparture(@RequestBody TourDepartureRequest request) {
        return ApiResponse.<TourDepartureResponse>builder()
                .result(tourDepartureService.create(request))
                .message("Created Successfully")
                .build();
    }

    @PostMapping("/{id}/open")
    public ApiResponse<TourDepartureResponse> open(@PathVariable int id) {
        return ApiResponse.<TourDepartureResponse>builder().result(tourDepartureService.open(id)).build();
    }

    @PutMapping("/{id}/vehicle")
    public ApiResponse<TourDepartureResponse> assignVehicle(@PathVariable int id,
            @jakarta.validation.Valid @RequestBody VehicleAssignment input) {
        return ApiResponse.<TourDepartureResponse>builder()
                .result(tourDepartureService.assignVehicle(id, input.vehicleId())).build();
    }

    @PutMapping("/{id}")
    public ApiResponse<TourDepartureResponse> updateTourDeparture(@PathVariable int id, @RequestBody TourDepartureRequest request) {
        return ApiResponse.<TourDepartureResponse>builder()
                .result(tourDepartureService.update(id, request))
                .message("Updated successfully")
                .build();
    }
}
