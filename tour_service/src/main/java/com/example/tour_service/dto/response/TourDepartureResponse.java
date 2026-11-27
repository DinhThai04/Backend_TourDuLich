package com.example.tour_service.dto.response;

import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class TourDepartureResponse {
    int id;
    String tourCode;
    LocalDateTime departureDate;
    java.time.OffsetDateTime bookingCloseAt;
    LocalDateTime returnDate;
    Short availableSeats;
    Integer capacity;
    String operationalStatus;
    String operatingVehicleId;
    long version;
    int tourId;
    TourPriceResponse tourPrice;
}
