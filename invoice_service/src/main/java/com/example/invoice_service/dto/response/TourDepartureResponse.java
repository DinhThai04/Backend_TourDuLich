package com.example.invoice_service.dto.response;

import com.example.invoice_service.dto.response.TourPriceResponse;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class TourDepartureResponse {
    String id;
    String tourCode;
    String departureDate;
    String returnDate;
    String availableSeats;
    String tourId;
    TourPriceResponse tourPrice;
}
