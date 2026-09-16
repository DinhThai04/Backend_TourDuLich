package com.example.tour_service.dto.request;

import com.example.tour_service.entity.Tour;
import jakarta.persistence.Column;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class TourDepartureRequest {
    LocalDateTime departureDate;
    LocalDateTime returnDate;
    Short availableSeats;
    int tourId;
}
