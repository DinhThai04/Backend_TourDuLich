package com.example.tour_service.dto.response;

import lombok.*;
import lombok.experimental.FieldDefaults;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class ItineraryResponse {
    int id;
    int dayNumber;
    String title;
    String description;
    String meal;
}