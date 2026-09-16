package com.example.booking_service.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;
import lombok.experimental.FieldDefaults;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@JsonInclude(JsonInclude.Include.ALWAYS)

public class CustomerResponse {
    private String id;
    private String fullName;
    private String dateOfBirth;
    private String address;
    private String bookingType;
    private String gender;
    private String status;
    private BookingResponse booking;
}
