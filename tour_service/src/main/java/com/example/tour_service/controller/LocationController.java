package com.example.tour_service.controller;

import com.example.tour_service.dto.request.ApiResponse;
import com.example.tour_service.dto.request.LocationRequest;
import com.example.tour_service.dto.response.LocationResponse;
import com.example.tour_service.repository.LocationRepository;
import com.example.tour_service.service.LocationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import javax.xml.stream.Location;

@RestController
@RequestMapping("locations")
@RequiredArgsConstructor
public class LocationController {
    private final LocationService locationService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE) // Chỉ định rõ nhận Multipart
    public ApiResponse<LocationResponse> createLocation(@ModelAttribute LocationRequest request) { // Đổi thành @ModelAttribute
        return ApiResponse.<LocationResponse>builder()
                .result(locationService.createLocation(request))
                .message("Created Successfully")
                .build();
    }

    @PutMapping("/{id}")
    public ApiResponse<LocationResponse> updateLocation(@PathVariable int id, @ModelAttribute LocationRequest request) {
        return ApiResponse.<LocationResponse>builder()
                .result(locationService.updateLocation(id, request))
                .message("Updated Successfully")
                .build();
    }

//    @DeleteMapping("/{id}")
//    public ApiResponse<Void> deleteLocation(@PathVariable int id) {
//        locationService.deleteLocation(id);
//        return ApiResponse.<Void>builder()
//                .message("Deleted Successfully")
//                .build();
//    }

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

    /**
     * API tìm kiếm các điểm đi (departures) theo từ khóa.
     * Ví dụ: GET /locations/departures/search?keyword=Hà
     */
    @GetMapping("/departures/search")
    public ApiResponse<List<LocationResponse>> searchDepartureLocations(@RequestParam(name = "keyword") String keyword) {
        return ApiResponse.<List<LocationResponse>>builder()
                // 1. Bạn cần tạo phương thức này trong LocationService
                .result(locationService.searchDepartureLocations(keyword))
                .message("Searched departure locations successfully")
                .build();
    }

    /**
     * API tìm kiếm các điểm đến (destinations) theo từ khóa.
     * Ví dụ: GET /locations/destinations/search?keyword=Đà
     */
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