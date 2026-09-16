package com.example.tour_service.dto.request;

import lombok.*;
import lombok.experimental.FieldDefaults;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class ItineraryRequest {
    String title;
    String description;
    String meal;
    int tourId;
}