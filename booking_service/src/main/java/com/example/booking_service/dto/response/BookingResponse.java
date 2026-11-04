package com.example.booking_service.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@JsonInclude(JsonInclude.Include.ALWAYS)
public class BookingResponse {
    private String id;
    private String accountId;
    private String createdAt;
    private String status;
    private String tourDepartureId;
    private String message;
    private String reservationStatus;
    private String paymentDeadline;
    private boolean paymentRequiredNow;
    /** Giá đã chốt tại thời điểm đặt; null với booking cũ chưa có snapshot. */
    private String totalAmount;
    private String currency;
    private String priceSnapshotAt;
    private Map<String, String> unitPrices;
}
