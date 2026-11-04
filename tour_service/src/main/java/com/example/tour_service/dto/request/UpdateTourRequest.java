package com.example.tour_service.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;
import lombok.experimental.FieldDefaults;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class UpdateTourRequest {
    @NotBlank(message = "Chương trình tour không được để trống")
    @Size(max = 255, message = "Chương trình tour tối đa 255 ký tự")
    String tourProgram;

    @NotBlank(message = "Tên tour không được để trống")
    @Size(max = 255, message = "Tên tour tối đa 255 ký tự")
    String tourTitle;

    @NotBlank(message = "Mô tả tour không được để trống")
    @Size(max = 255, message = "Mô tả tour tối đa 255 ký tự")
    String description;

    @Min(value = 1, message = "Thời lượng tour phải lớn hơn 0")
    int duration;

    @Min(value = 1, message = "Điểm khởi hành không hợp lệ")
    int departureLocationId;

    @Min(value = 1, message = "Điểm đến không hợp lệ")
    int destinationLocationId;

    BigDecimal basePrice;

    @NotBlank(message = "Phương tiện không được để trống")
    String vehicleId;

    @NotNull(message = "Bảng giá tour không được để trống")
    Long tourPriceId;

    List<String> url;
    List<MultipartFile> files;
}
