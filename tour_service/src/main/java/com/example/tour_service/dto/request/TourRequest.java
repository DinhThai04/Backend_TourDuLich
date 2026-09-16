package com.example.tour_service.dto.request;


import lombok.*;
import lombok.experimental.FieldDefaults;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.web.multipart.MultipartFile;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class TourRequest {
    String tourProgram;
    String tourTitle;
    String description;
    int duration;
    int departureLocationId;
    int destinationLocationId;
    BigDecimal basePrice;
    String vehicleId;
    Long tourPriceId;
    
    List<MultipartFile> files;
}