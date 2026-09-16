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

    /*****
     *
     * @param id
     * @return
     */
    @GetMapping("/{id}")
    public ApiResponse<BookingResponse> getBookingsById(@PathVariable Long id) {
        return ApiResponse.<BookingResponse>builder()
                .result(bookingService.getBookingById(id))
                .message("Got successfully")
                .build();
    }

    /*****
     *
     * @return
     */
    @GetMapping("/all")
    public ApiResponse<List<BookingResponse>> getAllBookings() {
        return ApiResponse.<List<BookingResponse>>builder()
                .result(bookingService.getAllBookings())
                .message("Got successfully")
                .build();
    }

    /*****
     *
     * @param request
     * @return
     */
    @PostMapping("/create")
    public ApiResponse<BookingResponse> createBooking(
            @RequestBody BookingRequest request) {
                return ApiResponse.<BookingResponse>builder()
                .result(bookingService.createBooking(request))
                .message("Created successfully")
                .build();
    }

    /*****
     *
     * @param id
     * @return
     */
    @PutMapping("/status/{id}")
    public ApiResponse<BookingResponse> updateBookingStatus(@PathVariable Long id) {
        return ApiResponse.<BookingResponse>builder()
                .result(bookingService.updateBookingStatus(id))
                .message("Updated booking status successfully")
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
