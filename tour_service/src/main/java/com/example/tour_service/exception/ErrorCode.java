package com.example.tour_service.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import lombok.Getter;

@Getter
public enum ErrorCode {
    UNCATEGORIZED_EXCEPTION(9999, "Lỗi hệ thống, vui lòng thử lại sau", HttpStatus.INTERNAL_SERVER_ERROR),
    INVALID_KEY(1001, "Lỗi hệ thống, vui lòng thử lại sau", HttpStatus.BAD_REQUEST),
    UNAUTHENTICATED(1006, "Bạn chưa đăng nhập hoặc phiên đã hết hạn", HttpStatus.UNAUTHORIZED),
    UNAUTHORIZED(1007, "Bạn không có quyền thực hiện thao tác này", HttpStatus.FORBIDDEN),
    TOUR_NOT_FOUND(1013, "Không tìm thấy tour", HttpStatus.NOT_FOUND),
    TOUR_DEPARTURE_NOT_FOUND(1014, "Không tìm thấy lịch khởi hành", HttpStatus.NOT_FOUND),
    ITINERARY_NOT_FOUND(1015, "Không tìm thấy lịch trình", HttpStatus.NOT_FOUND),
    LOCATION_NOT_FOUND(1016, "Không tìm thấy địa điểm", HttpStatus.NOT_FOUND),
    VEHICLE_NOT_FOUND(1017, "Không tìm thấy phương tiện", HttpStatus.NOT_FOUND),
    ;

    ErrorCode(int code, String message, HttpStatusCode statusCode) {
        this.code = code;
        this.message = message;
        this.statusCode = statusCode;
    }

    private final int code;
    private final String message;
    private final HttpStatusCode statusCode;
}