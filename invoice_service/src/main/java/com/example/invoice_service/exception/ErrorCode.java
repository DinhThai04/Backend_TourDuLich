package com.example.invoice_service.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import lombok.Getter;

@Getter
public enum ErrorCode {
    UNCATEGORIZED_EXCEPTION(9999, "Lỗi hệ thống, vui lòng thử lại sau", HttpStatus.INTERNAL_SERVER_ERROR),
    INVALID_KEY(1001, "Lỗi hệ thống, vui lòng thử lại sau", HttpStatus.BAD_REQUEST),
    USER_NOT_EXISTED(1005, "Không tìm thấy người dùng", HttpStatus.NOT_FOUND),
    UNAUTHENTICATED(1006, "Bạn chưa đăng nhập hoặc phiên đã hết hạn", HttpStatus.UNAUTHORIZED),
    UNAUTHORIZED(1007, "Bạn không có quyền thực hiện thao tác này", HttpStatus.FORBIDDEN),
    TOUR_DEPARTURE_NOT_EXISTED(1013, "Không tìm thấy lịch khởi hành", HttpStatus.NOT_FOUND),
    BOOKING_NOT_EXISTED(1014, "Không tìm thấy đơn đặt tour", HttpStatus.NOT_FOUND),
    RESOURCE_NOT_FOUND(1015, "Không tìm thấy tài nguyên", HttpStatus.NOT_FOUND),
    DATA_INTEGRITY_ERROR(1016, "Dữ liệu không nhất quán, vui lòng thử lại", HttpStatus.CONFLICT);

    ErrorCode(int code, String message, HttpStatusCode statusCode) {
        this.code = code;
        this.message = message;
        this.statusCode = statusCode;
    }

    private final int code;
    private final String message;
    private final HttpStatusCode statusCode;
}
