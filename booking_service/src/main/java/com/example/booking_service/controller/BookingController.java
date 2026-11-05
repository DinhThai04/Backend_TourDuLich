package com.example.booking_service.controller;

import com.example.booking_service.dto.request.BookingRequest;
import com.example.booking_service.dto.response.ApiResponse;
import com.example.booking_service.dto.response.BookingResponse;
import com.example.booking_service.service.BookingService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/book")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class BookingController {

    private final BookingService bookingService;

    @GetMapping("/{id}")
    public ApiResponse<BookingResponse> getBookingsById(@PathVariable Long id) {
        return ApiResponse.<BookingResponse>builder()
                .result(bookingService.getBookingById(id))
                .message("Got successfully")
                .build();
    }

    @GetMapping("/all")
    public ApiResponse<List<BookingResponse>> getAllBookings(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false, defaultValue = "false") boolean mine) {
        return ApiResponse.<List<BookingResponse>>builder()
                .result(bookingService.getAllBookings(page, size, mine))
                .message("Got successfully")
                .build();
    }

    @PostMapping("/create")
    public ApiResponse<BookingResponse> createBooking(
            @jakarta.validation.Valid @RequestBody BookingRequest request) {
                return ApiResponse.<BookingResponse>builder()
                .result(bookingService.createBooking(request))
                .message("Created successfully")
                .build();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<BookingResponse> deleteBooking(@PathVariable Long id) {
        return ApiResponse.<BookingResponse>builder()
                .result(bookingService.deleteBooking(id))
                .message("Delete booking successfully")
                .build();
    }
}
