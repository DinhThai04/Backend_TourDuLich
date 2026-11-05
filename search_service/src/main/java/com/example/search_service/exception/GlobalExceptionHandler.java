package com.example.search_service.exception;

import java.util.Map;
import java.util.Objects;

import com.example.search_service.dto.ApiResponse;
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
    @org.springframework.web.bind.annotation.ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    org.springframework.http.ResponseEntity<ApiResponse> status(org.springframework.web.server.ResponseStatusException error) {
        // Errors use 1000 + HTTP status (success is 1000), so 400 becomes 1400.
        return org.springframework.http.ResponseEntity.status(error.getStatusCode()).body(ApiResponse.builder().code(1000 + error.getStatusCode().value()).message(error.getReason()).build());
    }

    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    ResponseEntity<ApiResponse> missingParameter(org.springframework.web.bind.MissingServletRequestParameterException exception) {
        return badRequest("Thiếu tham số bắt buộc: " + exception.getParameterName());
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiResponse> invalidParameterType(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException exception) {
        // Echoing the raw value would reflect hostile input back to the caller.
        return badRequest("Tham số không hợp lệ: " + exception.getName());
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ResponseEntity<ApiResponse> unreadableBody(Exception exception) {
        return badRequest("Dữ liệu JSON không hợp lệ");
    }

    /** Form/@ModelAttribute binding errors that do not arrive as MethodArgumentNotValidException. */
    @ExceptionHandler(org.springframework.validation.BindException.class)
    ResponseEntity<ApiResponse> invalidBinding(org.springframework.validation.BindException exception) {
        return badRequest("Dữ liệu gửi lên không hợp lệ");
    }

    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    ResponseEntity<ApiResponse> invalidConstraint(jakarta.validation.ConstraintViolationException exception) {
        return badRequest("Dữ liệu gửi lên không hợp lệ");
    }

    /** Pagination/filter guards reject unusable input with 400 instead of a 500 from Elasticsearch. */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiResponse> invalidRequest(IllegalArgumentException exception) {
        return badRequest(exception.getMessage());
    }

    /** Routing mistakes are the caller's fault; the Exception catch-all would report them as server faults. */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ResponseEntity<ApiResponse> missingResource(Exception exception) {
        return ResponseEntity.status(404).body(ApiResponse.builder().code(1404).message("Không tìm thấy tài nguyên").build());
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiResponse> unsupportedMethod(Exception exception) {
        return ResponseEntity.status(405).body(ApiResponse.builder().code(1405).message("Phương thức HTTP không được hỗ trợ").build());
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiResponse> unsupportedMediaType(Exception exception) {
        return ResponseEntity.status(415).body(ApiResponse.builder().code(1415).message("Định dạng nội dung không được hỗ trợ").build());
    }

    /** An Elasticsearch outage is a temporary dependency failure, not a malformed request. */
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    ResponseEntity<ApiResponse> searchUnavailable(RuntimeException exception) {
        log.warn("Search backend unavailable: {}", exception.getClass().getSimpleName());
        return ResponseEntity.status(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.builder().code(1503).message("Dịch vụ tìm kiếm tạm thời không khả dụng; vui lòng thử lại").build());
    }

    private static ResponseEntity<ApiResponse> badRequest(String message) {
        return ResponseEntity.badRequest().body(ApiResponse.builder().code(1400).message(message).build());
    }


    private static final String MIN_ATTRIBUTE = "min";

    @ExceptionHandler(value = Exception.class)
    ResponseEntity<ApiResponse> handlingUnexpectedException(Exception exception) {
        log.error("Exception: ", exception);
        ErrorCode errorCode = ErrorCode.UNCATEGORIZED_EXCEPTION;

        // An unexpected failure is a server error, not a malformed request from the caller.
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
                        .message(errorCode.getMessage())
                        .build());
    }

    @ExceptionHandler(value = MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse> handlingValidation(MethodArgumentNotValidException exception) {
        String enumKey = exception.getFieldError().getDefaultMessage();

        ErrorCode errorCode = ErrorCode.INVALID_KEY;
        Map<String, Object> attributes = null;
        try {
            errorCode = ErrorCode.valueOf(enumKey);

            var constraintViolation = exception.getBindingResult().getAllErrors().getFirst()
                    .unwrap(ConstraintViolation.class);

            attributes = constraintViolation.getConstraintDescriptor().getAttributes();

        } catch (IllegalArgumentException e) {

        }

        ApiResponse apiResponse = new ApiResponse();

        apiResponse.setCode(errorCode.getCode());
        apiResponse.setMessage(
                Objects.nonNull(attributes)
                        ? mapAttribute(errorCode.getMessage(), attributes)
                        : errorCode.getMessage());

        return ResponseEntity.badRequest().body(apiResponse);
    }

    private String mapAttribute(String message, Map<String, Object> attributes) {
        String minValue = String.valueOf(attributes.get(MIN_ATTRIBUTE));

        return message.replace("{" + MIN_ATTRIBUTE + "}", minValue);
    }
}