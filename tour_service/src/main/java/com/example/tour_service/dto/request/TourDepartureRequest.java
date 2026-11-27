package com.example.tour_service.dto.request;

import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class TourDepartureRequest {
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = com.example.tour_service.config.VietnamLocalDateTimeDeserializer.class)
    LocalDateTime departureDate;
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = com.example.tour_service.config.VietnamLocalDateTimeDeserializer.class)
    LocalDateTime returnDate;
    Short availableSeats;
    Integer capacity;
    Long expectedVersion;
    boolean confirmCapacity;
    int tourId;
}
