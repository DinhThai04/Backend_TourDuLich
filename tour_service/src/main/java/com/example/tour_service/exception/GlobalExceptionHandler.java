package com.example.tour_service.exception;

import java.util.Map;
import java.util.Objects;

import jakarta.validation.ConstraintViolation;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import com.example.tour_service.dto.request.ApiResponse;

import lombok.extern.slf4j.Slf4j;

@ControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    private static final String MIN_ATTRIBUTE = "min";

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ResponseEntity<ApiResponse> invalidDepartureInput(Exception exception) {
        return ResponseEntity.badRequest().body(ApiResponse.builder().code(1400).message("Dữ liệu JSON không hợp lệ; sức chứa/phiên bản phải là số nguyên và ngày giờ theo ISO").build());
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

    @ExceptionHandler(org.springframework.web.multipart.support.MissingServletRequestPartException.class)
    ResponseEntity<ApiResponse> missingPart(org.springframework.web.multipart.support.MissingServletRequestPartException exception) {
        return badRequest("Thiếu phần dữ liệu bắt buộc: " + exception.getRequestPartName());
    }

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

    /** Form/@ModelAttribute binding errors that do not arrive as MethodArgumentNotValidException. */
    @ExceptionHandler(org.springframework.validation.BindException.class)
    ResponseEntity<ApiResponse> invalidBinding(org.springframework.validation.BindException exception) {
        return badRequest("Dữ liệu gửi lên không hợp lệ");
    }

    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    ResponseEntity<ApiResponse> invalidConstraint(jakarta.validation.ConstraintViolationException exception) {
        return badRequest("Dữ liệu gửi lên không hợp lệ");
    }

    private static ResponseEntity<ApiResponse> badRequest(String message) {
        return ResponseEntity.badRequest().body(ApiResponse.builder().code(1400).message(message).build());
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    ResponseEntity<ApiResponse> departureConflict(org.springframework.web.server.ResponseStatusException exception) {
        // Same 1000 + HTTP status convention as the other tour responses, whatever the status is.
        return ResponseEntity.status(exception.getStatusCode()).body(ApiResponse.builder()
                .code(1000 + exception.getStatusCode().value()).message(exception.getReason()).build());
    }
    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    ResponseEntity<ApiResponse> staleDeparture(Exception exception) {
        return ResponseEntity.status(409).body(ApiResponse.builder().code(1409).message("Lịch đã thay đổi; vui lòng tải lại").build());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiResponse> invalidImage(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.builder().code(1400).message(exception.getMessage()).build());
    }
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<ApiResponse> imageTooLarge(Exception exception) {
        return ResponseEntity.status(413).body(ApiResponse.builder().code(1413).message("Mỗi ảnh tối đa 5 MB; mỗi lần tối đa 10 ảnh").build());
    }
    @ExceptionHandler(feign.FeignException.class)
    ResponseEntity<ApiResponse> mediaRequestFailed(feign.FeignException exception) {
        String message = "Dịch vụ ảnh hoặc bảng giá chưa phản hồi. Vui lòng thử lại";
        if (exception.status() == 400) {
            try { message = new com.fasterxml.jackson.databind.ObjectMapper().readTree(exception.contentUTF8()).path("message").asText(message); }
            catch (java.io.IOException ignored) {}
        }
        return ResponseEntity.status(exception.status() == 400 ? 400 : 502)
                .body(ApiResponse.builder().code(exception.status() == 400 ? 1400 : 1502).message(message).build());
    }

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
                        .message(errorCode.getMessage())
                        .build());
    }

    @ExceptionHandler(value = MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse> handlingValidation(MethodArgumentNotValidException exception) {
        var fieldError = exception.getBindingResult().getFieldError();
        // Query/form binding failures also arrive here and their default message is Spring's
        // conversion text, which names internal types and annotation class names. /tours/search is
        // public, so only the parameter name is echoed, same shape as invalidParameterType above.
        if (fieldError != null && "typeMismatch".equals(fieldError.getCode()))
            return badRequest("Tham số không hợp lệ: " + fieldError.getField());

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
