package com.example.tour_service.dto.internal;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReleaseHoldResponse {
    private Integer departureId;
    private Integer seatsReleased;
    private Short availableSeatsAfterRelease;
    private Integer version;
    private Boolean success;
    private String message;
}