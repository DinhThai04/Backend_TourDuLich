package com.example.pricing_service.service;

import com.example.pricing_service.dto.request.TourPriceRequest;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class VndPricePolicy {
    private static final BigDecimal MAX = new BigDecimal("9999999999");
    private VndPricePolicy() {}

    static void validate(TourPriceRequest request) {
        if (request == null) fail("Thiếu bảng giá");
        amount(request.getAdultPrice(), true, "Giá người lớn");
        amount(request.getChildPrice(), false, "Giá trẻ em");
        amount(request.getToddlerPrice(), false, "Giá trẻ nhỏ");
        amount(request.getInfantPrice(), false, "Giá em bé");
        amount(request.getSingleSupplementPrice(), false, "Phụ thu phòng đơn");
    }

    private static void amount(BigDecimal value, boolean positive, String field) {
        if (value == null || value.signum() < 0 || positive && value.signum() == 0
                || value.compareTo(MAX) > 0 || value.stripTrailingZeros().scale() > 0)
            fail(field + " phải là số nguyên VND " + (positive ? "từ 1" : "từ 0") + " đến 9.999.999.999");
    }

    private static void fail(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
