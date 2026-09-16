package com.example.invoice_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerResponse {
    private String id;
    private String fullName;
    private String birthdate;
    private String address;
    private String bookingType;
    private String gender;
    private String status;
}
