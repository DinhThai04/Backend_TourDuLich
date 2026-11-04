package com.example.booking_service.exception;

import java.util.Map;
import java.util.Objects;

import jakarta.validation.ConstraintViolation;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import com.example.booking_service.dto.response.ApiResponse;

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
    /** Routing failures are checked servlet exceptions, so the RuntimeException catch-all never sees them. */
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
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiResponse> handlingInvalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.builder()
                .code(1400).message(exception.getMessage()).build());
    }

    /** "?id=abc" is a malformed path/query value: the caller must be told 400, not 500. */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiResponse> invalidParameterType(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException exception) {
        log.warn("Bad parameter '{}' with value '{}'", exception.getName(), exception.getValue());
        // Echoing the raw value would reflect hostile input back to the caller.
        return ResponseEntity.badRequest().body(ApiResponse.builder()
                .code(1400).message("Tham số không hợp lệ: " + exception.getName()).build());
    }

    /** A required "?tourId=" left off is the same caller mistake; the default resolver would answer 400 with no envelope. */
    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    ResponseEntity<ApiResponse> missingParameter(
            org.springframework.web.bind.MissingServletRequestParameterException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.builder()
                .code(1400).message("Thiếu tham số bắt buộc: " + exception.getParameterName()).build());
    }

    private static final String MIN_ATTRIBUTE = "min";

    /** Database failures and impossible arithmetic are server faults, never the caller's mistake. */
    @ExceptionHandler({org.springframework.dao.DataAccessException.class, ArithmeticException.class})
    ResponseEntity<ApiResponse> handlingServerFailure(RuntimeException exception) {
        log.error("Server failure: ", exception);
        ApiResponse apiResponse = new ApiResponse();

        apiResponse.setCode(ErrorCode.UNCATEGORIZED_EXCEPTION.getCode());
        apiResponse.setMessage(ErrorCode.UNCATEGORIZED_EXCEPTION.getMessage());

        return ResponseEntity.internalServerError().body(apiResponse);
    }

    @ExceptionHandler(value = RuntimeException.class)
    ResponseEntity<ApiResponse> handlingRuntimeException(RuntimeException exception) {
        log.error("Exception: ", exception);
        ApiResponse apiResponse = new ApiResponse();

        apiResponse.setCode(ErrorCode.UNCATEGORIZED_EXCEPTION.getCode());
        apiResponse.setMessage(ErrorCode.UNCATEGORIZED_EXCEPTION.getMessage());

        // Unexpected failures are server errors (500), not bad requests sent by the caller.
        return ResponseEntity.internalServerError().body(apiResponse);
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
                        .message(errorCode.getMessage())
                        .build());
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

        ApiResponse apiResponse = new ApiResponse();

        apiResponse.setCode(errorCode.getCode());
        apiResponse.setMessage(
                Objects.nonNull(attributes)
                        ? mapAttribute(errorCode.getMessage(), attributes)
                        : enumKey == null ? errorCode.getMessage() : fieldError.getDefaultMessage());

        return ResponseEntity.badRequest().body(apiResponse);
    }

    private String mapAttribute(String message, Map<String, Object> attributes) {
        String minValue = String.valueOf(attributes.get(MIN_ATTRIBUTE));

        return message.replace("{" + MIN_ATTRIBUTE + "}", minValue);
    }
}
