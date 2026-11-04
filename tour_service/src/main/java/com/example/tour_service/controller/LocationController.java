package com.example.tour_service.controller;

import com.example.tour_service.dto.request.ApiResponse;
import com.example.tour_service.dto.request.LocationRequest;
import com.example.tour_service.dto.response.LocationResponse;
import com.example.tour_service.service.LocationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("locations")
@RequiredArgsConstructor
public class LocationController {
    private final LocationService locationService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<LocationResponse> createLocation(@Valid @ModelAttribute LocationRequest request) {
        return ApiResponse.<LocationResponse>builder()
                .result(locationService.createLocation(request))
                .message("Created Successfully")
                .build();
    }

    @PutMapping("/{id}")
    public ApiResponse<LocationResponse> updateLocation(@PathVariable int id, @Valid @ModelAttribute LocationRequest request) {
        return ApiResponse.<LocationResponse>builder()
                .result(locationService.updateLocation(id, request))
                .message("Updated Successfully")
                .build();
    }

    @GetMapping("/all")
    public ApiResponse<List<LocationResponse>> getAllLocation() {
        return ApiResponse.<List<LocationResponse>>builder()
                .result(locationService.getAllLocation())
                .message("Fetched All Locations")
                .build();
    }

    @GetMapping("/departures")
    public ApiResponse<List<LocationResponse>> getAllDepartureLocations() {
        return ApiResponse.<List<LocationResponse>>builder()
                .result(locationService.getAllDepartureLocations())
                .message("Fetched All Departure Locations")
                .build();
    }

    @GetMapping("/destinations")
    public ApiResponse<List<LocationResponse>> getAllDestinationLocations() {
        return ApiResponse.<List<LocationResponse>>builder()
                .result(locationService.getAllDestinationLocations())
                .message("Fetched All Destination Locations")
                .build();
    }

    @GetMapping("/departures/search")
    public ApiResponse<List<LocationResponse>> searchDepartureLocations(@RequestParam(name = "keyword") String keyword) {
        return ApiResponse.<List<LocationResponse>>builder()
                .result(locationService.searchDepartureLocations(keyword))
                .message("Searched departure locations successfully")
                .build();
    }

    @GetMapping("/destinations/search")
    public ApiResponse<List<LocationResponse>> searchDestinationLocations(@RequestParam(name = "keyword") String keyword) {
        return ApiResponse.<List<LocationResponse>>builder()
                .result(locationService.searchDestinationLocations(keyword))
                .message("Searched destination locations successfully")
                .build();
    }

    @GetMapping("/destinations/attractive")
    public ApiResponse<List<LocationResponse>> attractiveDestinationLocations() {
        return ApiResponse.<List<LocationResponse>>builder()
                .result(locationService.attractiveDestinationLocations())
                .message("List attractive destination locations successfully")
                .build();
    }
}