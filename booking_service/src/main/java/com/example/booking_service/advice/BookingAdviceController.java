package com.example.booking_service.advice;

import com.example.booking_service.dto.response.ApiResponse;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/advice")
public class BookingAdviceController {
    private final BookingAdviceService service;
    private final ReviewEligibilityService reviews;
    public BookingAdviceController(BookingAdviceService service, ReviewEligibilityService reviews) { this.service=service; this.reviews=reviews; }
    @GetMapping("/review-eligibility")
    public ResponseEntity<ApiResponse<ReviewEligibilityService.Result>> reviewEligibility(@RequestParam long tourId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.<ReviewEligibilityService.Result>builder().result(reviews.check(tourId)).build());
    }
    @GetMapping("/mine")
    public ResponseEntity<ApiResponse<BookingAdviceService.Page>> mine(@RequestParam(required=false) Integer bookingId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.<BookingAdviceService.Page>builder().result(service.mine(bookingId)).build());
    }
}
