package com.example.booking_service.controller;

import com.example.booking_service.dto.response.ApiResponse;
import com.example.booking_service.reservation.BookingPickupService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/book")
@RequiredArgsConstructor
public class BookingPickupController {
    private final BookingPickupService pickups;

    public record AdminSelection(@NotBlank @Pattern(regexp = "[a-f0-9]{64}") String confirmationKey,
                                 @NotBlank @Size(max = 500) String reason) {}

    public record Selection(@NotBlank @Pattern(regexp = "[a-f0-9]{64}") String confirmationKey) {}

    @GetMapping("/{id}/pickup")
    public ApiResponse<BookingPickupService.Choices> options(@PathVariable int id) {
        return ApiResponse.<BookingPickupService.Choices>builder().result(pickups.options(id)).build();
    }

    @PutMapping("/{id}/pickup")
    public ApiResponse<BookingPickupService.Choices> select(@PathVariable int id, @Valid @RequestBody Selection input) {
        return ApiResponse.<BookingPickupService.Choices>builder().result(pickups.select(id, input.confirmationKey())).build();
    }

    @GetMapping("/{id}/pickup/admin")
    public ApiResponse<BookingPickupService.Choices> adminOptions(@PathVariable int id) {
        return ApiResponse.<BookingPickupService.Choices>builder().result(pickups.adminOptions(id)).build();
    }

    @PutMapping("/{id}/pickup/admin")
    public ApiResponse<BookingPickupService.Choices> assign(@PathVariable int id, @Valid @RequestBody AdminSelection input) {
        return ApiResponse.<BookingPickupService.Choices>builder()
                .result(pickups.assign(id, input.confirmationKey(), input.reason())).build();
    }

    @PostMapping("/departures/{id}/pickup-announcements")
    public ApiResponse<BookingPickupService.Announcement> announce(@PathVariable int id) {
        return ApiResponse.<BookingPickupService.Announcement>builder().result(pickups.announce(id)).build();
    }
}
