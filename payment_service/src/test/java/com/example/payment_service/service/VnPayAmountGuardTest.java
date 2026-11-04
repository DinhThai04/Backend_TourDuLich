package com.example.payment_service.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Both gateways take an integer VND amount; a bad price table must reach the client as a 400, never an ArithmeticException 500. */
class VnPayAmountGuardTest {
    private static Map<String, Object> price(String adult, String child, String toddler, String infant) {
        return Map.of("adult_price", new BigDecimal(adult), "child_price", new BigDecimal(child),
                "toddler_price", new BigDecimal(toddler), "infant_price", new BigDecimal(infant));
    }

    private static List<Map<String, Object>> passengers(String... types) {
        return java.util.Arrays.stream(types).map(type -> Map.<String, Object>of("booking_type", type)).toList();
    }

    private static final Map<String, Object> FARES = price("8490000", "6390000", "0", "500");

    @Test void wholeVndFaresAddUpToTheProviderAmount() {
        assertEquals(23_370_000L, VnPayOrderService.calculateAmount(passengers("ADULT", "ADULT", "CHILD"), FARES));
        assertEquals(500L, VnPayOrderService.calculateAmount(passengers("INFANT"), FARES));
    }

    @Test void fractionalFareTableIsAValidationError() {
        var fractional = price("8490000.50", "6390000", "0", "500");
        assertEquals("Bảng giá phải là số nguyên VND", assertThrows(IllegalArgumentException.class,
                () -> VnPayOrderService.calculateAmount(passengers("ADULT"), fractional)).getMessage());
    }

    @Test void negativeFareIsRejectedBeforeItCanReduceTheTotal() {
        var corrupted = price("8490000", "-6390000", "0", "500");
        assertEquals("Giá vé không hợp lệ", assertThrows(IllegalArgumentException.class,
                () -> VnPayOrderService.calculateAmount(passengers("ADULT", "CHILD"), corrupted)).getMessage());
    }

    @Test void amountsOutsideTheProviderNumericRangeAreRejected() {
        assertEquals("Số tiền không hợp lệ", assertThrows(IllegalArgumentException.class,
                () -> VnPayOrderService.calculateAmount(passengers("TODDLER"), FARES)).getMessage());
        var huge = price("10000000000", "6390000", "0", "500");
        assertEquals("Số tiền không hợp lệ", assertThrows(IllegalArgumentException.class,
                () -> VnPayOrderService.calculateAmount(passengers("ADULT"), huge)).getMessage());
    }

    @Test void unknownPassengerTypeAndEmptyBookingFailLoudly() {
        assertEquals("Booking chưa có hành khách", assertThrows(IllegalArgumentException.class,
                () -> VnPayOrderService.calculateAmount(List.of(), FARES)).getMessage());
        assertEquals("Loại hành khách không hợp lệ", assertThrows(IllegalArgumentException.class,
                () -> VnPayOrderService.calculateAmount(passengers("PET"), FARES)).getMessage());
    }
}
