package com.example.tour_service.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;
import lombok.experimental.FieldDefaults;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class ItineraryRequest {
    @NotBlank(message = "Tiêu đề lịch trình không được để trống")
    @Size(max = 255, message = "Tiêu đề lịch trình tối đa 255 ký tự")
    String title;

    @NotBlank(message = "Mô tả lịch trình không được để trống")
    String description;

    @NotBlank(message = "Bữa ăn không được để trống")
    @Size(max = 255, message = "Bữa ăn tối đa 255 ký tự")
    String meal;

    @Min(value = 1, message = "Tour của lịch trình không hợp lệ")
    int tourId;
}