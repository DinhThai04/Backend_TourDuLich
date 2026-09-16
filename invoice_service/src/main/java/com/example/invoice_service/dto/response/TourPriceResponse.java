package com.example.invoice_service.dto.response;

import lombok.*;
import lombok.experimental.FieldDefaults;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class TourPriceResponse {
    String id;
    String adultPrice;
    String childPrice;
    String toddlerPrice;
    String infantPrice;
    String singleSupplementPrice;
}