package com.example.invoice_service.dto.request;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class TourDepartureRequest {
    String departureDate;
    String returnDate;
    String availableSeats;
    String tourId;
}
