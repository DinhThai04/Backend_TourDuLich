package com.example.tour_service.dto.response;

import lombok.*;
import lombok.experimental.FieldDefaults;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class TourResponse {
    int id;
    String tourProgram;
    String tourTitle;
    String description;
    int duration;
    LocationResponse departureCity;
    LocationResponse destinationCity;
    BigDecimal basePrice;
    VehicleResponse vehicle;
    TourPriceResponse tourPrice;
    List<TourDepartureResponse> departures;
    List<String> imageIds;
}