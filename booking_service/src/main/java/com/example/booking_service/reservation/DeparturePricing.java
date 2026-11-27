package com.example.booking_service.reservation;

import java.util.Map;

public final class DeparturePricing {
    private DeparturePricing() {}

    public static long priceId(Map<String, Object> departure) {
        // Older Tour instances omit this field during a rolling deployment.
        Object value = departure.getOrDefault("tour_price_id", departure.get("tour_id"));
        long id = value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
        if (id < 1) throw new IllegalArgumentException("Mã bảng giá không hợp lệ");
        return id;
    }
}
