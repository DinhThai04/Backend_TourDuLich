package com.example.invoice_service.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingRequest {
    private String bookingId;
    private String userId;
    private String tourDepartureId;
    private List<CustomerRequest> listOfCustomers;
}
