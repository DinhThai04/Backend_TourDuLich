package com.example.tour_service.advice;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import static com.example.tour_service.advice.AdviceModels.*;

@RestControllerAdvice(assignableTypes=AdviceController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdviceExceptionHandler {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AdviceExceptionHandler.class);
    @ExceptionHandler(AdviceException.class)
    public ResponseEntity<ErrorResponse> advice(AdviceException failure) {
        return response(failure.status(),failure.getMessage());
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> malformed() { return response(400,"JSON tìm tour không hợp lệ."); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception failure) {
        // Same envelope as a real catalogue outage, so the stack has to be recorded here or the
        // two are indistinguishable in production and the retry hint keeps pointing at a bug.
        LOG.error("advice_request_failed", failure);
        return response(503,"Chưa thể kiểm tra dữ liệu tour. Vui lòng thử lại sau.");
    }
    private ResponseEntity<ErrorResponse> response(int status, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(status*10,message,null,
                new ErrorDetail(status==400?"INVALID_REQUEST":status==413?"PAYLOAD_TOO_LARGE":"CATALOG_UNAVAILABLE",status>=500,null)));
    }
}
