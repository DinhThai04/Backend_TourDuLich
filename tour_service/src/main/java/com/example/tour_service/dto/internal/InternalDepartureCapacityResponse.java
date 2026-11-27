package com.example.tour_service.dto.internal;

import lombok.*;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InternalDepartureCapacityResponse {
    private Integer departureId;
    private Integer tourId;
    private String tourCode;
    private java.time.LocalDateTime departureDate;
    private java.time.LocalDateTime returnDate;
    private Short capacity;
    private Short availableSeats;
    private Integer version;
    private Integer paidSeats;
    private Integer heldSeats;
    private Integer overdueHolds;
    private Integer historyCount;
    private Boolean hasHistory;
    private Boolean capacityMismatch;
    private java.util.List<String> issues;
    private java.util.List<String> blockingIssues;
    private java.util.List<String> warnings;
}