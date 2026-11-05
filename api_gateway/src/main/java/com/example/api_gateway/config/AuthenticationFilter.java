package com.example.api_gateway.config;


import com.example.api_gateway.dto.response.ApiResponse;
import com.example.api_gateway.dto.response.IntrospectResponse;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PACKAGE, makeFinal = true)
public class AuthenticationFilter implements GlobalFilter, Ordered {
     /** What introspection concluded; the routed call itself is outside this decision. */
     private enum AuthDecision { ACCEPT, REJECT, UNAVAILABLE }

     IdentityService identityService;
     ObjectMapper objectMapper;
     AuthCircuitBreaker authCircuitBreaker;
     IntrospectionCache introspectionCache;

     // Token introspection stays behind authentication at the edge: the gateway and search service
     // call auth directly, so publishing it would expose an unthrottled validity oracle for any token.
     private static final List<String> PUBLIC_POST = List.of(
             "/auth/users/login", "/auth/users/logout", "/auth/users/google", "/auth/users/register",
             "/auth/users/refresh", "/pricing/prices/batch",
             "/payment/momo/ipn");
     // Chỉ các đường dẫn đọc công khai đúng hình dạng dưới đây; không dùng ".*" để tránh
     // vô tình mở công khai một endpoint quản trị thêm vào sau này.
     private static final List<String> PUBLIC_GET = List.of(
             "/auth/reviews/high-rating",
             "/auth/reviews/tour/[0-9]+",
             "/file/media/download/[^/]+",
             "/file/media/tours",
             "/file/owner/[^/]+",
             "/pricing/prices/[0-9]+",
             "/tour/tours/[0-9]+",
             "/tour/tours/list",
             "/tour/tours/random-3",
             "/tour/tours/search",
             "/tour/locations/all",
             "/tour/locations/departures",
             "/tour/locations/destinations",
             "/tour/locations/departures/search",
             "/tour/locations/destinations/search",
             "/tour/locations/destinations/attractive",
             "/tour/vehicles/list",
             "/tour/tour-departures/[0-9]+",
             "/tour/tour-departures/tour/[0-9]+",
             "/tour/itineraries/tour/[0-9]+",
             "/search/tours",
             "/search/tours/search",
             "/payment/vnpay/ipn",
             "/payment/vnpay/bookings/return",
             "/payment/momo/bookings/return");

    @Value("${app.api-prefix}")
    @NonFinal
    private String apiPrefix;
    @Value("${app.mail.internal-api-key:}")
    @NonFinal
    private String internalMailKey;
    // A 503 from the breaker is a temporary hold, so tell clients how long to wait.
    @Value("${app.auth.breaker-open-ms:10000}")
    @NonFinal
    private long authBreakerOpenMs;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if ((apiPrefix + "/booking/internal/mail/reminders/dispatch").equals(exchange.getRequest().getURI().getPath())) {
            String supplied=exchange.getRequest().getHeaders().getFirst("X-Internal-Mail-Key");
            if(internalMailKey==null || internalMailKey.length()<32) {
                return writeJson(exchange.getResponse(), HttpStatus.SERVICE_UNAVAILABLE, ApiResponse.builder()
                        .code(1503)
                        .message("Mail dispatch is not configured")
                        .build());
            }
            if(supplied==null || !MessageDigest.isEqual(internalMailKey.getBytes(StandardCharsets.UTF_8),
                    supplied.getBytes(StandardCharsets.UTF_8))) return unauthenticated(exchange.getResponse());
            if(exchange.getRequest().getMethod()!=HttpMethod.POST) {
                return writeJson(exchange.getResponse(), HttpStatus.METHOD_NOT_ALLOWED, ApiResponse.builder()
                        .code(1405)
                        .message("Only POST is allowed")
                        .build());
            }
            return chain.filter(exchange); // booking service independently checks the same credential
        }
        // Introspection is called by the gateway itself and by search; routing it from the edge
        // would let any logged-in account test arbitrary tokens for validity.
        if ((apiPrefix + "/auth/users/introspect").equals(exchange.getRequest().getURI().getPath())) {
            return writeJson(exchange.getResponse(), HttpStatus.FORBIDDEN, ApiResponse.builder()
                    .code(1403)
                    .message("Đường dẫn này chỉ dùng cho giao tiếp nội bộ giữa các dịch vụ")
                    .build());
        }
        // 1. Logic check Public Endpoint (Giữ nguyên)
        if (isPublicEndpoint(exchange.getRequest())) {
            return chain.filter(exchange);
        }

        // 2. Logic lấy Token (Giữ nguyên)
        List<String> authHeader = exchange.getRequest().getHeaders().get(HttpHeaders.AUTHORIZATION);
        if (CollectionUtils.isEmpty(authHeader) || authHeader.size()!=1 || !authHeader.getFirst().startsWith("Bearer ") || authHeader.getFirst().length()<=7)
            return unauthenticated(exchange.getResponse());

        String token = authHeader.get(0).substring(7);

        // 3. A token accepted moments ago is reused instead of calling the auth service again.
        if (introspectionCache.isAccepted(token)) {
            return chain.filter(exchange);
        }

        // 4. Introspect the token. An auth-service outage must NOT look like bad credentials:
        //    return 503 so clients can retry instead of silently logging the user out.
        //    The routed call runs only after this Mono settles, so a downstream failure can never
        //    be mistaken for an auth failure or trip the auth circuit breaker.
        return introspect(token)
                .flatMap(decision -> switch (decision) {
                    case ACCEPT -> chain.filter(exchange);
                    case REJECT -> unauthenticated(exchange.getResponse());
                    case UNAVAILABLE -> unavailable(exchange.getResponse());
                });
    }

    private Mono<AuthDecision> introspect(String token) {
        if (!authCircuitBreaker.allowRequest()) {
            log.warn("Auth circuit open; rejecting without calling identity service");
            return Mono.just(AuthDecision.UNAVAILABLE);
        }
        return identityService.introspect(token)
                .flatMap(introspectResponse -> {
                    authCircuitBreaker.recordSuccess();
                    boolean valid = isValid(introspectResponse);
                    if (valid) introspectionCache.remember(token);
                    return Mono.just(valid ? AuthDecision.ACCEPT : AuthDecision.REJECT);
                })
                // No answer at all says nothing about the token: reject rather than let it through.
                .switchIfEmpty(Mono.just(AuthDecision.REJECT))
                .onErrorResume(throwable -> {
                    // The identity service answered with a client error (malformed/expired token):
                    // that is a rejected credential, not an outage and not an internal failure.
                    if (throwable instanceof org.springframework.web.reactive.function.client.WebClientResponseException responseError
                            && responseError.getStatusCode().is4xxClientError()) {
                        authCircuitBreaker.recordSuccess();
                        return Mono.just(AuthDecision.REJECT);
                    }
                    if (DownstreamErrors.isUnavailable(throwable)) {
                        authCircuitBreaker.recordFailure();
                        log.error("Identity service unavailable: {}", throwable.toString());
                        return Mono.just(AuthDecision.UNAVAILABLE);
                    }
                    log.error("Gateway/child service failure: {}", throwable.toString());
                    return Mono.error(throwable);
                });
    }

    private boolean isValid(ApiResponse<IntrospectResponse> introspectResponse) {
        return introspectResponse != null
                && introspectResponse.getResult() != null
                && introspectResponse.getResult().isValid();
    }

    @Override
    public int getOrder() {
        return -1;
    }

    private boolean isPublicEndpoint(ServerHttpRequest request) {
        String path = request.getURI().getPath();
        if(HttpMethod.GET.equals(request.getMethod()) && (apiPrefix+"/ai/policies").equals(path)) return true;
        // Anonymous questions receive a login prompt. Supplied credentials must pass introspection,
        // then booking_service verifies the JWT again and applies its own strict owner predicate.
        if(HttpMethod.POST.equals(request.getMethod()) && (apiPrefix+"/ai/booking-help").equals(path)
                && !request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) return true;
        // Public advice has no account privileges; expose only these three POST operations.
        if (HttpMethod.POST.equals(request.getMethod())
                && List.of(apiPrefix + "/ai/conversations", apiPrefix + "/ai/chat",
                           apiPrefix + "/ai/conversations/reset").contains(path)) return true;
        if ((HttpMethod.POST.equals(request.getMethod())
                && (apiPrefix + "/tour/advice/search").equals(path))
                || (HttpMethod.GET.equals(request.getMethod())
                && (apiPrefix + "/tour/advice/locations").equals(path))) return true;
        String relative = path.startsWith(apiPrefix) ? path.substring(apiPrefix.length()) : path;
        // Management summary lives below the public departure catalog, but must still introspect credentials.
        if(relative.matches("/tour/tour-departures/[^/]+/capacity/?")) return false;
        if (HttpMethod.POST.equals(request.getMethod())) return PUBLIC_POST.contains(relative);
        return HttpMethod.GET.equals(request.getMethod())
                && PUBLIC_GET.stream().anyMatch(relative::matches);
    }

    Mono<Void> unauthenticated(ServerHttpResponse response) {
        return writeJson(response, HttpStatus.UNAUTHORIZED, ApiResponse.builder()
                .code(1401)
                .message("Bạn chưa đăng nhập hoặc phiên đã hết hạn")
                .build());
    }

    Mono<Void> unavailable(ServerHttpResponse response) {
        response.getHeaders().add(HttpHeaders.RETRY_AFTER,
                String.valueOf(Math.max(1, Math.ceilDiv(authBreakerOpenMs, 1000L))));
        return writeJson(response, HttpStatus.SERVICE_UNAVAILABLE, ApiResponse.builder()
                .code(1503)
                .message("Dịch vụ xác thực tạm thời chưa sẵn sàng. Vui lòng thử lại sau.")
                .build());
    }

    private Mono<Void> writeJson(ServerHttpResponse response, HttpStatus status, ApiResponse<?> apiResponse) {
        String body;
        try {
            body = objectMapper.writeValueAsString(apiResponse);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        response.setStatusCode(status);
        response.getHeaders().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);

        return response.writeWith(
                Mono.just(response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }
}
