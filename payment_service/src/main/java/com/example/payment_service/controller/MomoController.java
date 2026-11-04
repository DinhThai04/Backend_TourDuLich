package com.example.payment_service.controller;

import com.example.payment_service.dto.request.ApiResponse;
import com.example.payment_service.dto.request.MoMoRequest;
import com.example.payment_service.dto.response.BookingQuoteResponse;
import com.example.payment_service.dto.response.MoMoResponse;
import com.example.payment_service.service.MoMoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@lombok.extern.slf4j.Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/momo")
public class MoMoController {
    private final MoMoService moMoService;

    @PostMapping("/bookings")
    public ResponseEntity<String> createPayment(@jakarta.validation.Valid @RequestBody MoMoRequest request, @AuthenticationPrincipal Jwt jwt) {
        try {
            String payUrl = moMoService.createPayment(request.getBookingId(), request.getExpectedAmount(), Integer.parseInt(jwt.getSubject()));
            return ResponseEntity.ok(payUrl);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode()).body(e.getReason());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to prepare MoMo booking {}", request.getBookingId(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Đã xảy ra lỗi khi tạo thanh toán!");
        }
    }

    @GetMapping("/bookings/{bookingId}/quote")
    public ApiResponse<BookingQuoteResponse> quote(@PathVariable int bookingId, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.<BookingQuoteResponse>builder()
                .result(moMoService.quote(bookingId, Integer.parseInt(jwt.getSubject())))
                .message("Got successfully")
                .build();
    }

    @GetMapping("/bookings/return")
    public ApiResponse<MoMoResponse> returnPayment(@RequestParam Map<String, String> allRequestParams) {
        return ApiResponse.<MoMoResponse>builder()
                .result(moMoService.handlePaymentReturn(allRequestParams))
                .message("Got successfully")
                .build();
    }

    // MoMo AIO posts its IPN as JSON without a user token; legacy replays may still use a form body.
    // Both shapes are merged into one map and the signature is verified before any settlement.
    @PostMapping(path = "/ipn", consumes = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> ipn(@RequestBody(required = false) Map<String, Object> body,
                                   @RequestParam Map<String, String> form) {
        return respond(body, form);
    }

    @PostMapping(path = "/ipn", consumes = org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Map<String, Object> ipnForm(@RequestParam Map<String, String> form) {
        return respond(null, form);
    }

    private Map<String, Object> respond(Map<String, Object> body, Map<String, String> form) {
        Map<String, String> fields = new java.util.LinkedHashMap<>(form);
        if (body != null) {
            body.forEach((key, value) -> {
                if (key != null && value != null) fields.put(key, String.valueOf(value));
            });
        }
        if (fields.isEmpty()) {
            return Map.of("status", 99, "message", "Dữ liệu IPN MoMo không hợp lệ");
        }
        try {
            var outcome = moMoService.handlePaymentReturn(fields);
            // resultCode 9000 has no final answer yet: acking it would stop MoMo from sending the
            // retry IPN that actually settles the booking.
            return "pending".equals(outcome.getStatus())
                    ? Map.of("status", 99, "message", "Giao dịch chưa có kết quả cuối cùng")
                    : Map.of("status", 0, "message", "Success");
        } catch (com.example.payment_service.service.VnPayRejection rejection) {
            return Map.of("status", 99, "message", rejection.getMessage());
        } catch (RuntimeException ex) {
            return Map.of("status", 99, "message", "Retry later");
        }
    }
}
