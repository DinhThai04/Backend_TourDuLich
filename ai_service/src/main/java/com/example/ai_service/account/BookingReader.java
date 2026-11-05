package com.example.ai_service.account;

import java.time.OffsetDateTime;
import java.util.List;

public interface BookingReader {
    int MAX_PAGE_SIZE = 20;

    record Booking(int bookingId,int tourId,String tourProgram,String tourTitle,OffsetDateTime createdAt,
            OffsetDateTime departureAt,String state,String reservationStatus,OffsetDateTime paymentDeadline,
            long remainingSeconds,OffsetDateTime checkedAt,String ruleVersion) {}
    record Page(List<Booking> bookings,boolean hasMore,OffsetDateTime checkedAt) {}
    Page mine(String authorization,Integer bookingId);
}
