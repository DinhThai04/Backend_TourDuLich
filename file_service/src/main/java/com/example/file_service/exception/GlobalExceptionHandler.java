package com.example.file_service.exception;

import java.util.Map;
import java.util.Objects;

import jakarta.validation.ConstraintViolation;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import com.example.file_service.dto.request.ApiResponse;

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

    private static final String MIN_ATTRIBUTE = "min";

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    ResponseEntity<ApiResponse> requestLimit(org.springframework.web.server.ResponseStatusException exception) {
        // File service codes follow 1000 + HTTP status (1400 bad request, 1429 quota, 1502 storage).
        int status = exception.getStatusCode().value();
        return ResponseEntity.status(exception.getStatusCode()).body(ApiResponse.builder()
                .code(1000 + status).message(exception.getReason()).build());
    }

    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    ResponseEntity<ApiResponse> missingParameter(org.springframework.web.bind.MissingServletRequestParameterException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.builder().code(1400)
                .message("Thiếu tham số bắt buộc: " + exception.getParameterName()).build());
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiResponse> invalidParameterType(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException exception) {
        // Echoing the raw value would reflect hostile input back to the caller.
        return ResponseEntity.badRequest().body(ApiResponse.builder().code(1400)
                .message("Tham số không hợp lệ: " + exception.getName()).build());
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ResponseEntity<ApiResponse> missingResource(Exception exception) {
        return ResponseEntity.status(404).body(ApiResponse.builder().code(1404)
                .message("Không tìm thấy tài nguyên").build());
    }

    /** Routing mistakes are the caller's fault; the Exception catch-all would report them as server faults. */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiResponse> unsupportedMethod(Exception exception) {
        return ResponseEntity.status(405).body(ApiResponse.builder().code(1405)
                .message("Phương thức HTTP không được hỗ trợ").build());
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiResponse> unsupportedMediaType(Exception exception) {
        return ResponseEntity.status(415).body(ApiResponse.builder().code(1415)
                .message("Định dạng nội dung không được hỗ trợ").build());
    }
    @ExceptionHandler(MediaStorageException.class)
    ResponseEntity<ApiResponse> failedDeletion(MediaStorageException exception) {
        return ResponseEntity.status(502).body(ApiResponse.builder().code(1502).message(exception.getMessage()).build());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiResponse> invalidImage(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.builder().code(1400).message(exception.getMessage()).build());
    }
    @ExceptionHandler({org.springframework.web.multipart.MultipartException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class})
    ResponseEntity<ApiResponse> malformedUpload(Exception exception) {
        return ResponseEntity.badRequest().body(ApiResponse.builder().code(1400)
                .message("Yêu cầu tải tệp không hợp lệ").build());
    }
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<ApiResponse> imageTooLarge(Exception exception) {
        return ResponseEntity.status(413).body(ApiResponse.builder().code(1413).message("Mỗi ảnh tối đa 5 MB; tối đa 10 ảnh và tổng 25 MB mỗi lần").build());
    }
    @ExceptionHandler(java.io.IOException.class)
    ResponseEntity<ApiResponse> storageUnavailable(java.io.IOException exception) {
        log.warn("Image storage request failed: {}", exception.getClass().getSimpleName());
        return ResponseEntity.status(502).body(ApiResponse.builder().code(1502).message("Chưa xử lý được ảnh Cloudinary. Vui lòng thử lại").build());
    }

    @ExceptionHandler(value = Exception.class)
    ResponseEntity<ApiResponse> handlingUnexpectedException(Exception exception) {
        if (storageFailure(exception)) {
            log.warn("Image storage request failed: {}", exception.getClass().getSimpleName());
            return ResponseEntity.status(502).body(ApiResponse.builder().code(1502)
                    .message("Chưa xử lý được ảnh Cloudinary. Vui lòng thử lại").build());
        }
        log.error("Exception: ", exception);
        ApiResponse apiResponse = new ApiResponse();

        apiResponse.setCode(ErrorCode.UNCATEGORIZED_EXCEPTION.getCode());
        apiResponse.setMessage(ErrorCode.UNCATEGORIZED_EXCEPTION.getMessage());

        return ResponseEntity.status(ErrorCode.UNCATEGORIZED_EXCEPTION.getStatusCode()).body(apiResponse);
    }

    /** Wrapped storage errors are a gateway problem, not a malformed client request. */
    private static boolean storageFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.io.IOException
                    || cause instanceof MediaStorageException
                    || cause.getClass().getName().startsWith("com.cloudinary.")) return true;
            if (cause.getCause() == cause) return false;
        }
        return false;
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
