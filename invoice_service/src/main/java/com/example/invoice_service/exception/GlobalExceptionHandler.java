package com.example.invoice_service.exception;

import java.util.Map;
import java.util.Objects;

import com.example.invoice_service.dto.response.ApiResponse;
import jakarta.validation.ConstraintViolation;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import lombok.extern.slf4j.Slf4j;

@ControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ResponseEntity<ApiResponse> unreadableBody(Exception exception) {
        // A truncated or malformed body is a bad request, not a server fault.
        return ResponseEntity.badRequest().body(ApiResponse.builder()
                .code(1400).message("Dữ liệu JSON không hợp lệ").build());
    }
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiResponse> invalidParameterType(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException exception) {
        // Echoing the raw value would reflect hostile input back to the caller.
        return ResponseEntity.badRequest().body(ApiResponse.builder()
                .code(1400).message("Tham số không hợp lệ: " + exception.getName()).build());
    }
    /** Routing mistakes are the caller's fault; the Exception catch-all would report them as server faults. */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ResponseEntity<ApiResponse> missingResource(Exception exception) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND).body(ApiResponse.builder()
                .code(1404).message("Không tìm thấy tài nguyên").build());
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiResponse> unsupportedMethod(Exception exception) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.METHOD_NOT_ALLOWED).body(ApiResponse.builder()
                .code(1405).message("Phương thức HTTP không được hỗ trợ").build());
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiResponse> unsupportedMediaType(Exception exception) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(ApiResponse.builder()
                .code(1415).message("Định dạng nội dung không được hỗ trợ").build());
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    ResponseEntity<ApiResponse> handlingStatus(org.springframework.web.server.ResponseStatusException exception) {
        // Errors use 1000 + HTTP status (success is 1000), so 404 becomes 1404.
        return ResponseEntity.status(exception.getStatusCode()).body(ApiResponse.builder()
                .code(1000 + exception.getStatusCode().value()).message(exception.getReason()).build());
    }

    private static final String MIN_ATTRIBUTE = "min";

    @ExceptionHandler(value = Exception.class)
    ResponseEntity<ApiResponse> handlingRuntimeException(Exception exception) {
        log.error("Exception: ", exception);
        ErrorCode errorCode = ErrorCode.UNCATEGORIZED_EXCEPTION;

        // Unexpected failures are server errors (500), not bad requests sent by the caller.
        return ResponseEntity.status(errorCode.getStatusCode()).body(ApiResponse.builder()
                .code(errorCode.getCode())
                .message(errorCode.getMessage())
                .build());
    }

    @ExceptionHandler(value = AppException.class)
    ResponseEntity<ApiResponse> handlingAppException(AppException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        ApiResponse apiResponse = new ApiResponse();

        apiResponse.setCode(errorCode.getCode());
        apiResponse.setMessage(errorCode.getMessage());

        return ResponseEntity.status(errorCode.getStatusCode()).body(apiResponse);
    }

    @ExceptionHandler(value = AccessDeniedException.class)
    ResponseEntity<ApiResponse> handlingAccessDeniedException(AccessDeniedException exception) {
        ErrorCode errorCode = ErrorCode.UNAUTHORIZED;

        return ResponseEntity.status(errorCode.getStatusCode())
                .body(ApiResponse.builder()
                        .code(errorCode.getCode())
                        .message(denialReason(exception, errorCode))
                        .build());
    }

    /**
     * InvoiceAccess composes the reason shown to the user. The method-security interceptor throws the
     * fixed "Access Denied" text, which says nothing to a customer, so it stays behind the generic
     * permission message.
     */
    private static String denialReason(AccessDeniedException exception, ErrorCode errorCode) {
        String reason = exception.getMessage();
        return org.springframework.util.StringUtils.hasText(reason) && !"Access Denied".equals(reason)
                ? reason : errorCode.getMessage();
    }

    @ExceptionHandler(value = MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse> handlingValidation(MethodArgumentNotValidException exception) {
        var fieldError = exception.getBindingResult().getFieldError();
        String enumKey = fieldError == null ? null : fieldError.getDefaultMessage();

        ErrorCode errorCode = ErrorCode.INVALID_KEY;
        Map<String, Object> attributes = null;
        try {
            errorCode = ErrorCode.valueOf(enumKey);

            var constraintViolation = exception.getBindingResult().getAllErrors().getFirst()
                    .unwrap(ConstraintViolation.class);

            attributes = constraintViolation.getConstraintDescriptor().getAttributes();
        } catch (IllegalArgumentException | NullPointerException notAnEnumKey) {
            // Literal validation messages are already suitable for display to the user.
        }

        return ResponseEntity.badRequest().body(ApiResponse.builder()
                .code(errorCode.getCode())
                .message(Objects.nonNull(attributes)
                        ? mapAttribute(errorCode.getMessage(), attributes)
                        : enumKey == null ? errorCode.getMessage() : fieldError.getDefaultMessage())
                .build());
    }

    private String mapAttribute(String message, Map<String, Object> attributes) {
        String minValue = String.valueOf(attributes.get(MIN_ATTRIBUTE));

        return message.replace("{" + MIN_ATTRIBUTE + "}", minValue);
    }
}
