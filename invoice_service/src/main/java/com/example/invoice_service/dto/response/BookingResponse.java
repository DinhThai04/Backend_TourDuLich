package com.example.invoice_service.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BookingResponse {
    private String id;
    private String accountId;
    private String tourDepartureId;
    private String createAt;
    private String status;
    private List<CustomerResponse> listOfCustomers;
}
