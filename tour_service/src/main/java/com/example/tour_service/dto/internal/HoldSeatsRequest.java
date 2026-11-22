package com.example.tour_service.dto.internal;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HoldSeatsRequest {
    private Integer seats;
    private String bookingId;
    private java.time.LocalDateTime expiresAt;
}