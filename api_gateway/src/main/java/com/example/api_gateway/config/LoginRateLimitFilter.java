package com.example.api_gateway.config;

import com.example.api_gateway.dto.response.ApiResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal in-memory throttle for the credential endpoints. It is not a substitute for a shared
 * limiter, but it stops trivial credential-stuffing loops against a single gateway instance.
 * Login, registration, Google sign-in, token refresh and password change each have their own
 * per-client bucket.
 */
@Component
public class LoginRateLimitFilter implements GlobalFilter, Ordered {
    /** Hard ceiling: an attacker rotating source addresses must not grow this map without bound. */
    private static final int MAX_BUCKETS = 10_000;

    private record Bucket(long windowStart, int count) {}

    private final ObjectMapper objectMapper;
    private final Map<String, Integer> limitsByPath;
    private final long windowMillis;
    private final String retryAfter;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public LoginRateLimitFilter(ObjectMapper objectMapper,
                                @Value("${app.api-prefix:/api/v1}") String apiPrefix,
                                @Value("${app.auth.login-attempts-per-minute:20}") int loginLimit,
                                @Value("${app.auth.register-attempts-per-minute:5}") int registerLimit,
                                @Value("${app.auth.google-attempts-per-minute:10}") int googleLimit,
                                @Value("${app.auth.refresh-attempts-per-minute:30}") int refreshLimit,
                                @Value("${app.auth.password-change-attempts-per-minute:10}") int passwordChangeLimit,
                                @Value("${app.auth.login-window-ms:60000}") long windowMillis) {
        this.objectMapper = objectMapper;
        // Refresh needs a valid refresh token, so it is looser than login; the ceiling only exists
        // to bound how fast one stolen token can be replayed into new access tokens.
        this.limitsByPath = Map.of(
                apiPrefix + "/auth/users/login", Math.max(1, loginLimit),
                apiPrefix + "/auth/users/register", Math.max(1, registerLimit),
                apiPrefix + "/auth/users/google", Math.max(1, googleLimit),
                apiPrefix + "/auth/users/refresh", Math.max(1, refreshLimit),
                // Authenticated but still guesses the old password, so it needs its own bucket.
                apiPrefix + "/auth/users/Change-password", Math.max(1, passwordChangeLimit));
        this.windowMillis = Math.max(1000L, windowMillis);
        // Retry-After must match the real window, otherwise a shortened limit still tells clients to wait a minute.
        this.retryAfter = String.valueOf(Math.ceilDiv(this.windowMillis, 1000L));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!HttpMethod.POST.equals(exchange.getRequest().getMethod())) {
            return chain.filter(exchange);
        }
        String path = exchange.getRequest().getURI().getPath();
        Integer limit = limitsByPath.get(path);
        if (limit == null) {
            return chain.filter(exchange);
        }

        long now = System.currentTimeMillis();
        if (buckets.size() >= MAX_BUCKETS) {
            buckets.entrySet().removeIf(entry -> now - entry.getValue().windowStart() >= windowMillis);
            // Still full after pruning: evict one entry so new clients are tracked and memory is bounded.
            if (buckets.size() >= MAX_BUCKETS) buckets.remove(buckets.keySet().iterator().next());
        }

        String client = ClientAddress.of(exchange.getRequest());
        // The path is part of the key: one exhausted endpoint never blocks another.
        String key = path + "|" + client;
        Bucket bucket = buckets.compute(key, (ignored, current) ->
                current == null || now - current.windowStart() >= windowMillis
                        ? new Bucket(now, 1)
                        : new Bucket(current.windowStart(), current.count() + 1));

        if (bucket.count() > limit) {
            return tooManyRequests(exchange.getResponse());
        }
        return chain.filter(exchange);
    }

    private Mono<Void> tooManyRequests(ServerHttpResponse response) {
        ApiResponse<?> apiResponse = ApiResponse.builder()
                .code(1429)
                // Same wording as the AI admission throttle: one status must not speak two languages.
                .message("Bạn thao tác quá nhanh. Vui lòng thử lại sau.")
                .build();
        String body;
        try {
            body = objectMapper.writeValueAsString(apiResponse);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot serialize rate-limit response", error);
        }

        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        response.getHeaders().add(HttpHeaders.RETRY_AFTER, retryAfter);

        return response.writeWith(Mono.just(
                response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }

    @Override
    public int getOrder() {
        return -2;
    }
}
