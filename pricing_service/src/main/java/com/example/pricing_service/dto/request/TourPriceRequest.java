package com.example.pricing_service.dto.request;

import lombok.*;
import lombok.experimental.FieldDefaults;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class TourPriceRequest {
    BigDecimal adultPrice;
    BigDecimal childPrice;
    BigDecimal toddlerPrice;
    BigDecimal infantPrice;
    BigDecimal singleSupplementPrice;
}