package com.example.pricing_service.dto.internal;

import lombok.*;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InternalPriceResponse {
    private Long id;
    private BigDecimal adultPrice;
    private BigDecimal childPrice;
    private BigDecimal toddlerPrice;
    private BigDecimal infantPrice;
    private BigDecimal singleSupplementPrice;
}