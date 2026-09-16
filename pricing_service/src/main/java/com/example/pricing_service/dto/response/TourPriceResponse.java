package com.example.pricing_service.dto.response;

import lombok.*;
import lombok.experimental.FieldDefaults;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class TourPriceResponse {
    Long id;
    BigDecimal adultPrice;
    BigDecimal childPrice;
    BigDecimal toddlerPrice;
    BigDecimal infantPrice;
    BigDecimal singleSupplementPrice;
}