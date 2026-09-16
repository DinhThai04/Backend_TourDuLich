package com.example.tour_service.controller;

import com.example.tour_service.dto.request.ItineraryRequest;
import com.example.tour_service.dto.response.ApiResponse;
import com.example.tour_service.dto.response.ItineraryResponse;
import com.example.tour_service.service.ItineraryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("itineraries")
@RequiredArgsConstructor
public class ItineraryController {
    private final ItineraryService itineraryService;

    @GetMapping("/{id}")
    public ApiResponse<ItineraryResponse> getById(@PathVariable int id) {
        return ApiResponse.<ItineraryResponse>builder()
                .result(itineraryService.getById(id))
                .message("Got successfully")
                .build();
    }

    @GetMapping("/tour/{tourId}")
    public ApiResponse<List<ItineraryResponse>> getByTourId(@PathVariable int tourId) {
        return ApiResponse.<List<ItineraryResponse>>builder()
                .result(itineraryService.getByTourId(tourId))
                .message("Fetched successfully")
                .build();
    }

     @GetMapping("/all")
     public ApiResponse<List<ItineraryResponse>> getAll() {
         return ApiResponse.<List<ItineraryResponse>>builder()
         .result(itineraryService.getAll())
         .message("Fetched successfully")
         .build();
     }

    @PostMapping
    public ApiResponse<ItineraryResponse> create(@RequestBody ItineraryRequest request) {
        return ApiResponse.<ItineraryResponse>builder()
                .result(itineraryService.create(request))
                .message("Created successfully")
                .build();
    }

    @PutMapping("/{id}")
    public ApiResponse<ItineraryResponse> update(@PathVariable int id, @RequestBody ItineraryRequest request) {
        return ApiResponse.<ItineraryResponse>builder()
                .result(itineraryService.update(id, request))
                .message("Updated successfully")
                .build();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable int id) {
        itineraryService.delete(id);
        return ApiResponse.<Void>builder()
                .message("Deleted successfully")
                .build();
    }
}