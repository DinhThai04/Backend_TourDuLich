package com.example.tour_service.dto.request;

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
    String tourProgram;
    String tourTitle;
    String description;
    int duration;
    int departureLocationId;
    int destinationLocationId;
    BigDecimal basePrice;
    String vehicleId;
    Long tourPriceId;
    List<String> url;
    List<MultipartFile> files;
}
