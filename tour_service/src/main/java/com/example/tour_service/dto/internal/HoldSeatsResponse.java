package com.example.tour_service.dto.internal;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HoldSeatsResponse {
    private Integer departureId;
    private Integer seatsHeld;
    private Short availableSeatsAfterHold;
    private Integer version;
    private Boolean success;
    private String message;
}