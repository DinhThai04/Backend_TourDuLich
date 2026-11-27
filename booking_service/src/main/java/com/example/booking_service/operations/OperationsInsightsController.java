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
            @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate through,
            @RequestParam(required = false) Integer tourId, @RequestParam(required = false) String region) {
        return ok(insights.report(from, through, tourId, region));
    }

    @GetMapping("/invoice-transactions/{bookingId}")
    public ApiResponse<?> invoiceTransactions(@PathVariable int bookingId) {
        return ok(insights.invoiceTransactions(bookingId));
    }

    @GetMapping("/deliveries/{bookingId}")
    public ApiResponse<?> delivery(@PathVariable int bookingId) {
        return ok(insights.delivery(bookingId));
    }

    @PostMapping("/deliveries/{bookingId}/{id}/retry")
    public ApiResponse<?> retry(@PathVariable int bookingId, @PathVariable long id,
            @RequestParam(defaultValue = "false") boolean confirmUncertain) {
        insights.retryDelivery(bookingId, id, confirmUncertain);
        return ok(true);
    }

    @GetMapping("/audit")
    public ApiResponse<?> audit(@RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "20") int size) {
        return ok(insights.audit(page, size));
    }

    @GetMapping("/integrity")
    public ApiResponse<?> integrity() {
        return ok(insights.integrity());
    }

    @PostMapping("/integrity/rescan")
    public ApiResponse<?> rescan() {
        return ok(insights.rescan());
    }

    private ApiResponse<Object> ok(Object value) {
        return ApiResponse.builder().result(value).build();
    }
}
