package com.example.booking_service.change;

import com.example.booking_service.dto.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/changes")
public class BookingChangeController {

    private final BookingChangeService service;

    public BookingChangeController(BookingChangeService service) {
        this.service = service;
    }

    @GetMapping("/mine")
    public ApiResponse<Object> mine(@RequestParam(required = false) Integer page,
                                    @RequestParam(required = false) Integer size) {
        return ok(service.mine(page, size));
    }

    @GetMapping("/eligible")
    public ApiResponse<Object> eligible(@RequestParam(required = false) Integer page,
                                        @RequestParam(required = false) Integer size) {
        return ok(service.eligible(page, size));
    }

    @GetMapping("/options")
    public ApiResponse<Object> options(@RequestParam int bookingId) {
        return ok(service.options(bookingId));
    }

    @PostMapping("/customer")
    public ApiResponse<Object> customer(@Valid @RequestBody ChangeRequests.Customer request) {
        return ok(service.customer(request));
    }

    @GetMapping("/admin")
    public ApiResponse<Object> admin(@RequestParam(required = false) Integer tourId,
                                     @RequestParam(required = false) Integer page,
                                     @RequestParam(required = false) Integer size) {
        return ok(service.admin(tourId, page, size));
    }

    @PostMapping("/shop/preview")
    public ApiResponse<Object> preview(@Valid @RequestBody ChangeRequests.Shop request) {
        return ok(service.shop(request, true));
    }

    @PostMapping("/shop")
    public ApiResponse<Object> shop(@Valid @RequestBody ChangeRequests.Shop request) {
        return ok(service.shop(request, false));
    }

    @PostMapping("/{id}/respond")
    public ApiResponse<Object> respond(@PathVariable long id, @Valid @RequestBody ChangeRequests.Decision request) {
        return ok(service.respond(id, request));
    }

    @PostMapping("/{id}/review")
    public ApiResponse<Object> review(@PathVariable long id, @Valid @RequestBody ChangeRequests.Decision request) {
        return ok(service.review(id, request));
    }

    @PostMapping("/{id}/refund")
    public ApiResponse<Object> refund(@PathVariable long id, @Valid @RequestBody ChangeRequests.Refund request) {
        return ok(service.refund(id, request));
    }

    @PostMapping("/shop/{id}/activate")
    public ApiResponse<Object> activate(@PathVariable long id) {
        return ok(service.activate(id));
    }

    private ApiResponse<Object> ok(Object value) {
        return ApiResponse.builder().result(normalize(value)).build();
    }

    private Object normalize(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toString();
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toString();
        }
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach((key, item) -> {
                String name = key.toString();
                if (name.equals(name.toUpperCase(Locale.ROOT))) {
                    name = name.toLowerCase(Locale.ROOT);
                }
                result.put(name, normalize(item));
            });
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::normalize).toList();
        }
        return value;
    }
}
