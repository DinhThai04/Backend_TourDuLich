package com.example.api_gateway.config;


import com.example.api_gateway.dto.response.ApiResponse;
import com.example.api_gateway.service.IdentityService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import java.util.Arrays;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PACKAGE, makeFinal = true)
public class AuthenticationFilter implements GlobalFilter, Ordered {
     IdentityService identityService;
     ObjectMapper objectMapper;

     @NonFinal
     private String[] publicEndpoints = {
            "/auth/users/login",
            "/auth/users/logout",
            "/auth/users/google",
            "/auth/users/register",
            "/auth/users/refresh",
            "/auth/users/introspect",
            "/auth/reviews/high-rating",
            "/notification/email/send",
            "/file/media/download/.*",
            "/pricing/prices/.*",
            "/tour/tours/.*",
            "/tour/locations/.*",
            "/tour/vehicles/.*",
            "/tour/tour-departures/tour/.*",
            "/tour/tour-departures/.*",
            "/tour/itineraries/tour/.*",
            "/search/tours/search",
            "/search/tours",
            "/file/media/tours"
     };

    @Value("${app.api-prefix}")
    @NonFinal
    private String apiPrefix;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1. Logic check Public Endpoint (Giữ nguyên)
        if (isPublicEndpoint(exchange.getRequest())) {
            return chain.filter(exchange);
        }

        // 2. Logic lấy Token (Giữ nguyên)
        List<String> authHeader = exchange.getRequest().getHeaders().get(HttpHeaders.AUTHORIZATION);
        if (CollectionUtils.isEmpty(authHeader))
            return unauthenticated(exchange.getResponse());

        String token = authHeader.get(0).substring(7);

        // 3. LOGIC GỌI INTROSPECT VÀ XỬ LÝ LỖI PHÂN TÁCH
        return identityService.introspect(token)
                // --- BƯỚC QUAN TRỌNG NHẤT: BẮT LỖI RIÊNG CHO AUTH SERVICE ---
                .onErrorResume(throwable -> {
                    // Đây là nơi bắt lỗi nếu Identity Service (Port 8080) bị chết hoặc lỗi mạng
                    log.error("Lỗi khi gọi Identity Service: {}", throwable.getMessage());

                    // Chúng ta đánh dấu đây là lỗi Auth bằng cách ném ra 1 Exception riêng
                    // Hoặc trả về 401 ngay tại đây
                    return Mono.error(new RuntimeException("AUTH_FAILED"));
                })
                .flatMap(introspectResponse -> {
                    // Nếu Introspect thành công, check valid
                    if (introspectResponse.getResult().isValid()) {
                        // --- GỌI SERVICE ĐÍCH (Tour, Booking...) ---
                        // Nếu Tour Service chết, lỗi sẽ bắn ra từ dòng này
                        return chain.filter(exchange);
                    } else {
                        // Token hết hạn hoặc fake
                        return unauthenticated(exchange.getResponse());
                    }
                })
                // --- XỬ LÝ LỖI CUỐI CÙNG ---
                .onErrorResume(throwable -> {
                    // Nếu lỗi do Identity Service chết (đã đánh dấu ở trên) -> Trả 401
                    if ("AUTH_FAILED".equals(throwable.getMessage())) {
                        return unauthenticated(exchange.getResponse());
                    }

                    // QUAN TRỌNG: Nếu lỗi do Tour/Booking Service chết (ConnectException, 503...)
                    // Chúng ta KHÔNG được return unauthenticated().
                    // Hãy return Mono.error(throwable) để Gateway tự xử lý (sẽ ra 500 hoặc 503)
                    log.error("Lỗi Gateway/Service con: {}", throwable.getMessage());
                    return Mono.error(throwable);
                });
    }

    @Override
    public int getOrder() {
        return -1;
    }

    private boolean isPublicEndpoint(ServerHttpRequest request) {
        return Arrays.stream(publicEndpoints)
                .anyMatch(s -> request.getURI().getPath().matches(apiPrefix + s));
    }

    Mono<Void> unauthenticated(ServerHttpResponse response) {
        ApiResponse<?> apiResponse = ApiResponse.builder()
                .code(1401)
                .message("Unauthenticated")
                .build();

        String body = null;
        try {
            body = objectMapper.writeValueAsString(apiResponse);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);

        return response.writeWith(
                Mono.just(response.bufferFactory().wrap(body.getBytes())));
    }
}