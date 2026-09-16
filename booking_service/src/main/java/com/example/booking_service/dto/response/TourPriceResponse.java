package com.example.booking_service.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TourPriceResponse {
    Long id;
    BigDecimal adultPrice;
    BigDecimal childPrice;
    BigDecimal toddlerPrice;
    BigDecimal infantPrice;
    BigDecimal singleSupplementPrice;
}
