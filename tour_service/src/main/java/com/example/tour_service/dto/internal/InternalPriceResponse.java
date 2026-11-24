package com.example.tour_service.dto.internal;

import java.math.BigDecimal;

/** Read-only price contract returned by Pricing Service's authenticated internal API. */
public record InternalPriceResponse(
        Long id,
        BigDecimal adultPrice,
        BigDecimal childPrice,
        BigDecimal toddlerPrice,
        BigDecimal infantPrice,
        BigDecimal singleSupplementPrice) {
}
