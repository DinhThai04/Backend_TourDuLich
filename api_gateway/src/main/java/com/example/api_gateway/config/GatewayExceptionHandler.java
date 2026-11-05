package com.example.api_gateway.config;

import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import com.example.api_gateway.dto.response.ApiResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Renders proxy failures as the same JSON envelope used by the services, so an unreachable
 * downstream service answers 503 instead of the Netty default 500 error page.
 */
@Component
@Order(-2)
@Slf4j
@RequiredArgsConstructor
public class GatewayExceptionHandler implements ErrorWebExceptionHandler {

    private static final int DOWNSTREAM_UNAVAILABLE = 1503;
    private static final int UNEXPECTED_ERROR = 9999;

    private final ObjectMapper objectMapper;

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) return Mono.error(error);

        HttpStatus status = resolveStatus(error);
        logFailure(exchange, status, error);

        ApiResponse<Void> body = ApiResponse.<Void>builder()
                .code(codeFor(status))
                .message(messageFor(status))
                .build();
        return writeJson(response, status, body, error);
    }

    private static HttpStatus resolveStatus(Throwable error) {
        if (error instanceof ResponseStatusException statusError) {
            HttpStatusCode requested = statusError.getStatusCode();
            HttpStatus resolved = HttpStatus.resolve(requested.value());
            if (resolved != null) return resolved;
        }
        if (error instanceof org.springframework.web.reactive.function.client.WebClientResponseException responseError) {
            HttpStatus resolved = HttpStatus.resolve(responseError.getStatusCode().value());
            if (resolved != null) return resolved;
        }
        return DownstreamErrors.isUnavailable(error) ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.INTERNAL_SERVER_ERROR;
    }

    /** Same convention as the services: errors use 1000 + HTTP status, so 1404 is a 404. */
    private static int codeFor(HttpStatus status) {
        if (status == HttpStatus.SERVICE_UNAVAILABLE) return DOWNSTREAM_UNAVAILABLE;
        if (status.is4xxClientError()) return 1000 + status.value();
        return UNEXPECTED_ERROR;
    }

    private static String messageFor(HttpStatus status) {
        // The services answer the customer in Vietnamese; a proxy failure must not switch language
        // mid-screen. 1401 uses the same wording as the services' UNAUTHENTICATED code.
        if (status == HttpStatus.SERVICE_UNAVAILABLE) return "Dịch vụ tạm thời chưa sẵn sàng. Vui lòng thử lại sau.";
        if (status == HttpStatus.NOT_FOUND) return "Không tìm thấy tài nguyên";
        if (status == HttpStatus.METHOD_NOT_ALLOWED) return "Phương thức HTTP không được hỗ trợ";
        if (status.is4xxClientError()) return "Yêu cầu không hợp lệ";
        return "Lỗi hệ thống, vui lòng thử lại sau";
    }

    private static void logFailure(ServerWebExchange exchange, HttpStatus status, Throwable error) {
        String path = exchange.getRequest().getURI().getPath();
        if (status == HttpStatus.SERVICE_UNAVAILABLE) log.warn("Downstream unavailable for {}: {}", path, error.toString());
        else if (status.is5xxServerError()) log.error("Gateway request failed on {}: {}", path, error.toString());
        else log.warn("Gateway rejected {} with {}: {}", path, status.value(), error.toString());
    }

    private Mono<Void> writeJson(ServerHttpResponse response, HttpStatus status, ApiResponse<Void> body, Throwable original) {
        byte[] payload;
        try {
            payload = objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException serializationFailure) {
            return Mono.error(original);
        }
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(payload)));
    }
}
