package com.example.auth_service.exception;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import lombok.Getter;

@Getter
public enum ErrorCode {
    UNCATEGORIZED_EXCEPTION(9999, "Lỗi hệ thống, vui lòng thử lại sau", HttpStatus.INTERNAL_SERVER_ERROR),
    INVALID_KEY(1001, "Lỗi hệ thống, vui lòng thử lại sau", HttpStatus.BAD_REQUEST),
    USER_EXISTED(1002, "Tên tài khoản đã được sử dụng", HttpStatus.BAD_REQUEST),
    USERNAME_INVALID(1003, "Tên đăng nhập phải có ít nhất {min} ký tự", HttpStatus.BAD_REQUEST),
    INVALID_PASSWORD(1004, "Mật khẩu phải có ít nhất {min} ký tự", HttpStatus.BAD_REQUEST),
    OLD_PASSWORD_NOT_MATCH(1005, "Mật khẩu hiện tại không đúng", HttpStatus.BAD_REQUEST),
    USER_NOT_EXISTED(1026, "Tài khoản không tồn tại hoặc sai mật khẩu", HttpStatus.NOT_FOUND),
    USER_NOT_FOUND(1028, "Không tìm thấy người dùng", HttpStatus.NOT_FOUND),

    UNAUTHENTICATED(1006, "Bạn chưa đăng nhập hoặc phiên đã hết hạn", HttpStatus.UNAUTHORIZED),
    ACCOUNT_INACTIVE(1024, "Tài khoản đã bị khóa hoặc lưu trữ. Vui lòng liên hệ SGU Tour", HttpStatus.FORBIDDEN),
    UNAUTHORIZED(1007, "Bạn không có quyền thực hiện thao tác này", HttpStatus.FORBIDDEN),
    INVALID_DOB(1008, "Tuổi của bạn phải từ {min} trở lên", HttpStatus.BAD_REQUEST),
    INVALID_EMAIL(1009, "Địa chỉ email không hợp lệ", HttpStatus.BAD_REQUEST),
    EMAIL_IS_REQUIRED(1027, "Vui lòng nhập email", HttpStatus.BAD_REQUEST),
    INVALID_TOKEN(1010, "Mã xác thực không hợp lệ", HttpStatus.UNAUTHORIZED),
    EXPIRED_TOKEN(1011, "Mã xác thực đã hết hạn", HttpStatus.UNAUTHORIZED),
    PASSWORD_NOT_MATCH(1012, "Mật khẩu không khớp", HttpStatus.BAD_REQUEST),
    FILE_UPLOAD_FAILED(2001, "Tải tệp lên thất bại", HttpStatus.INTERNAL_SERVER_ERROR),
    ROLE_NOT_FOUND(1013, "Không tìm thấy vai trò", HttpStatus.NOT_FOUND),
    PERMISSION_NOT_FOUND(1014, "Không tìm thấy quyền", HttpStatus.NOT_FOUND),
    ROLE_ALREADY_EXISTS(1015, "Vai trò đã tồn tại", HttpStatus.BAD_REQUEST),
    PERMISSION_ALREADY_EXISTS(1016, "Quyền đã tồn tại", HttpStatus.BAD_REQUEST),
    ROLE_NAME_ALREADY_EXISTS(1017, "Tên vai trò đã tồn tại", HttpStatus.BAD_REQUEST),
    PERMISSION_NAME_ALREADY_EXISTS(1018, "Tên quyền đã tồn tại", HttpStatus.BAD_REQUEST),
    ROLE_PERMISSION_ALREADY_EXISTS(1019, "Vai trò đã có quyền này", HttpStatus.BAD_REQUEST),
    PERMISSION_ROLE_ALREADY_EXISTS(1020, "Quyền đã gắn với vai trò này", HttpStatus.BAD_REQUEST),
    // One generic conflict for both fields: a public registration must not reveal which one already exists.
    ACCOUNT_ALREADY_USED(1021, "Email hoặc tên tài khoản đã được sử dụng", HttpStatus.BAD_REQUEST),
    REGISTRATION_ROLE_UNAVAILABLE(1023, "Chưa cấu hình role khách hàng đang hoạt động; vui lòng liên hệ quản trị viên", HttpStatus.SERVICE_UNAVAILABLE),
    BAD_CREDENTIALS(1025, "Email hoặc mật khẩu không đúng", HttpStatus.UNAUTHORIZED),
    ROLE_NAME_RESERVED(1029, "Tên vai trò này được dành riêng cho vai trò hệ thống", HttpStatus.BAD_REQUEST),
    GOOGLE_LINK_REQUIRED(1030, "Email này đã có tài khoản mật khẩu. Hãy đăng nhập bằng email và mật khẩu, không dùng nút Google", HttpStatus.CONFLICT),
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
