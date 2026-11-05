package com.example.tour_service.dto.response;

import java.time.LocalDateTime;
import java.util.List;
public record DepartureCapacityResponse(int id,int tourId,String tourCode,LocalDateTime departureDate,LocalDateTime returnDate,
        Integer capacity,int availableSeats,long version,int paidSeats,int heldSeats,int overdueHolds,int historyCount,
        boolean scheduleEditable,boolean capacityReviewRequired,List<String> issues,List<String> blockingIssues,
        List<String> warnings) {}
