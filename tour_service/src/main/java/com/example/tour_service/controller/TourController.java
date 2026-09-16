package com.example.tour_service.controller;

import com.example.tour_service.dto.request.ApiResponse;
import com.example.tour_service.dto.request.TourRequest;
import com.example.tour_service.dto.request.TourSearchRequest;
import com.example.tour_service.dto.request.UpdateTourRequest;
import com.example.tour_service.dto.response.TourResponse;
import com.example.tour_service.service.TourService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/tours")
@RequiredArgsConstructor
public class TourController {

    private final TourService tourService;

    @GetMapping("/{id}")
    public ApiResponse<TourResponse> getTourById(@PathVariable Integer id) {
        return ApiResponse. <TourResponse>builder()
                .result(tourService.getTourById(id))
                .message("Got successfully")
                .build();
    }

    @GetMapping("/list")
    public ApiResponse<List<TourResponse>> getAll() {
        return ApiResponse.<List<TourResponse>>builder()
                .result(tourService.getAllTours())
                .message("Got successfully")
                .build();
    }

    @GetMapping("/random-3")
    public ApiResponse<List<TourResponse>> getRandom3Tours() {
        return ApiResponse.<List<TourResponse>>builder()
                .result(tourService.getRandom3Tours())
                .message("Fetched 3 random tours successfully")
                .build();
    }


    @PostMapping()
        public ApiResponse<TourResponse> createTour(@ModelAttribute TourRequest request) {
        return ApiResponse.<TourResponse>builder()
                .result(tourService.createTour(request))
                .message("Created successfully")
                .build();
    }

    @PutMapping("/{id}")
    public ApiResponse<TourResponse> updateTourALl(@PathVariable int id,  @ModelAttribute TourRequest request) {
        return ApiResponse.<TourResponse>builder()
                .result(tourService.updateTour(id, request))
                .message("Updated successfully")
                .build();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteTour(@PathVariable int id) {
        tourService.deleteTour(id);
        return ApiResponse.<Void>builder()
                .message("Deleted successfully")
                .build();
    }


    @GetMapping("/search")
    public ApiResponse<Page<TourResponse>> searchTours(
            TourSearchRequest request,
            @PageableDefault(
                    page = 0,         // Trang mặc định
                    size = 5        // Kích thước trang mặc định
//                    ,sort = "id",      // Sắp xếp mặc định theo cột "id"
//                    direction = Sort.Direction.DESC // Hướng sắp xếp
            )
            Pageable pageable         // Spring map ?page=0&size=10&sort=price,asc vào đây
    ) {
        return ApiResponse.<Page<TourResponse>>builder()
                .result(tourService.searchTours(request, pageable))
                .message("Search completed successfully")
                .build();
    }
//    @DeleteMapping("/{id}")
//    public ApiResponse<Void> deleteTour(@PathVariable int id) {
//        tourService.deleteTour(id);
//        return ApiResponse.<Void>builder()
//                .message("Deleted successfully")
//                .build();
//    }

}
