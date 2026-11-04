package com.example.tour_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;
import lombok.experimental.FieldDefaults;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class VehicleRequest {
    @NotBlank(message = "Mã phương tiện không được để trống")
    @Size(max = 255, message = "Mã phương tiện tối đa 255 ký tự")
    String id;

    @NotBlank(message = "Loại phương tiện không được để trống")
    @Size(max = 255, message = "Loại phương tiện tối đa 255 ký tự")
    String vehicleType;

}
