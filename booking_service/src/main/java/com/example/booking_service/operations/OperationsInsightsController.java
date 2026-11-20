package com.example.booking_service.operations;

import com.example.booking_service.dto.response.ApiResponse;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/operations")
public class OperationsInsightsController {
    private final OperationsInsights insights;

    public OperationsInsightsController(OperationsInsights insights) {
        this.insights = insights;
    }

    @GetMapping("/report")
    public ApiResponse<?> report(@RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate through) {
        return ok(insights.report(from, through));
    }

    @GetMapping("/deliveries/{bookingId}")
    public ApiResponse<?> delivery(@PathVariable int bookingId) {
        return ok(insights.delivery(bookingId));
    }

    @PostMapping("/deliveries/{bookingId}/{id}/retry")
    public ApiResponse<?> retry(@PathVariable int bookingId, @PathVariable long id) {
        insights.retryDelivery(bookingId, id);
        return ok(true);
    }

    @GetMapping("/audit")
    public ApiResponse<?> audit(@RequestParam(defaultValue = "0") int page) {
        return ok(insights.audit(page));
    }

    @GetMapping("/integrity")
    public ApiResponse<?> integrity() {
        return ok(insights.integrity());
    }

    private ApiResponse<Object> ok(Object value) {
        return ApiResponse.builder().result(value).build();
    }
}
