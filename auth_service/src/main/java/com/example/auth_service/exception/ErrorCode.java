package com.example.auth_service.exception;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import lombok.Getter;

@Getter
public enum ErrorCode {
    UNCATEGORIZED_EXCEPTION(9999, "Uncategorized error", HttpStatus.INTERNAL_SERVER_ERROR),
    INVALID_KEY(1001, "Uncategorized error", HttpStatus.BAD_REQUEST),
    USER_EXISTED(1002, "User existed", HttpStatus.BAD_REQUEST),
    USERNAME_INVALID(1003, "Username must be at least {min} characters", HttpStatus.BAD_REQUEST),
    INVALID_PASSWORD(1004, "Password must be at least {min} characters", HttpStatus.BAD_REQUEST),
    OLD_PASSWORD_NOT_MATCH(1005, "Old password not match", HttpStatus.BAD_REQUEST),
    USER_NOT_EXISTED(1005, "User not existed or wrong password ", HttpStatus.NOT_FOUND),
    User_name(10099, "User not existed", HttpStatus.NOT_FOUND),

    UNAUTHENTICATED(1006, "Unauthenticated", HttpStatus.UNAUTHORIZED),
    UNAUTHORIZED(1007, "You do not have permission", HttpStatus.FORBIDDEN),
    INVALID_DOB(1008, "Your age must be at least {min}", HttpStatus.BAD_REQUEST),
    INVALID_EMAIL(1009, "Invalid email address", HttpStatus.BAD_REQUEST),
    EMAIL_IS_REQUIRED(1009, "Email is required", HttpStatus.BAD_REQUEST),
    INVALID_TOKEN(1010, "Invalid token", HttpStatus.UNAUTHORIZED),
    EXPIRED_TOKEN(1011, "Token is expired", HttpStatus.UNAUTHORIZED),
    PASSWORD_NOT_MATCH(1012, "Password not match", HttpStatus.BAD_REQUEST),
    FILE_UPLOAD_FAILED(2001, "File upload failed", HttpStatus.INTERNAL_SERVER_ERROR),
    ROLE_NOT_FOUND(1013, "Role not found", HttpStatus.NOT_FOUND),
    PERMISSION_NOT_FOUND(1014, "Permission not found", HttpStatus.NOT_FOUND),
    ROLE_ALREADY_EXISTS(1015, "Role already exists", HttpStatus.BAD_REQUEST),
    PERMISSION_ALREADY_EXISTS(1016, "Permission already exists", HttpStatus.BAD_REQUEST),
    ROLE_NAME_ALREADY_EXISTS(1017, "Role name already exists", HttpStatus.BAD_REQUEST),
    PERMISSION_NAME_ALREADY_EXISTS(1018, "Permission name already exists", HttpStatus.BAD_REQUEST),
    ROLE_PERMISSION_ALREADY_EXISTS(1019, "Role permission already exists", HttpStatus.BAD_REQUEST),
    PERMISSION_ROLE_ALREADY_EXISTS(1020, "Permission role already exists", HttpStatus.BAD_REQUEST),
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