package com.example.auth_service.controller;


import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.example.auth_service.dto.request.ApiResponse;
import com.example.auth_service.dto.request.ReviewRequest;
import com.example.auth_service.dto.response.ReviewGroupResponse;
import com.example.auth_service.dto.response.ReviewResponse;
import com.example.auth_service.service.ReviewService;

import java.util.List;

@RestController
@RequestMapping("/reviews")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @GetMapping("/eligibility")
    public ResponseEntity<ApiResponse<com.example.auth_service.repository.httpclient.BookingReviewClient.Eligibility>> eligibility(@RequestParam long tourId) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(ApiResponse.<com.example.auth_service.repository.httpclient.BookingReviewClient.Eligibility>builder().result(reviewService.eligibility(tourId)).build());
    }

    @PostMapping
    public ApiResponse<ReviewResponse> create(@jakarta.validation.Valid @RequestBody ReviewRequest req) {
        return ApiResponse.<ReviewResponse>builder().result(reviewService.createReview(req)).build();
    }

    @GetMapping("/tour/{tourId}")
    public ApiResponse<List<ReviewResponse>> getByTour(
            @PathVariable Long tourId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.<List<ReviewResponse>>builder()
                .result(reviewService.getReviewsByTour(tourId, page, size))
                .build();
    }

    @GetMapping("/list")
    @org.springframework.security.access.prepost.PreAuthorize("@accountAccess.can('REVIEWS_GET_LIST')")
    public ApiResponse<List<ReviewGroupResponse>> getAllReViews(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.<List<ReviewGroupResponse>>builder()
                .result(reviewService.getAllReviewsGroupedByTour(page, size))
                .build();
    }

    @GetMapping("/high-rating")
    public ApiResponse<List<ReviewResponse>> getHighRatings(@RequestParam(name = "size", required = false, defaultValue = "4") Integer size) {
        return ApiResponse.<List<ReviewResponse>>builder()
                .result(reviewService.getHighRatings(size))
                .build();
    }

}
