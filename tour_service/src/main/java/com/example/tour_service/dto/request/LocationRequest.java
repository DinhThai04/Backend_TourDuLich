package com.example.tour_service.dto.request;

import com.example.tour_service.enums.LocationType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;
import lombok.experimental.FieldDefaults;
import org.springframework.web.multipart.MultipartFile;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class LocationRequest {
    @NotBlank(message = "Tên địa điểm không được để trống")
    @Size(max = 255, message = "Tên địa điểm tối đa 255 ký tự")
    String city;

    @NotNull(message = "Loại địa điểm không được để trống")
    LocationType type;

    MultipartFile img;

}
