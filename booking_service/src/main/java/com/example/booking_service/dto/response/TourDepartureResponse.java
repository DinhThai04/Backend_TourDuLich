package com.example.booking_service.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TourDepartureResponse {
    int id;
    String tourCode;
    LocalDateTime departureDate;
    LocalDateTime returnDate;
    Short availableSeats;
    int tourId;
    TourPriceResponse tourPrice;
}
