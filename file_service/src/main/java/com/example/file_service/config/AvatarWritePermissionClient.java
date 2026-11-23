package com.example.file_service.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/** Calls Auth's avatar-write-permission boundary instead of querying Auth-owned tables. */
@Component
public class AvatarWritePermissionClient {
    private final RestClient client;
    private final String internalKey;

    public AvatarWritePermissionClient(
            @Value("${app.auth.base-url:http://localhost:8080/auth}") String baseUrl,
            @Value("${app.auth.internal-key:}") String internalKey,
            @Value("${app.auth.connect-timeout:2s}") Duration connectTimeout,
            @Value("${app.auth.read-timeout:3s}") Duration readTimeout) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
        this.internalKey = internalKey;
    }

    public void requireWritable(String ownerId, boolean lockTarget) {
        if (internalKey == null || internalKey.isBlank()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "Auth internal API key is not configured");
        }
        String token = currentToken();
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,
                    "Cần đăng nhập để cập nhật ảnh đại diện");
        }
        try {
            client.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/internal/auth/users/{ownerId}/avatar-write-permission")
                            .queryParam("lockTarget", lockTarget)
                            .build(ownerId))
                    .header("Authorization", "Bearer " + token)
                    .header("X-Internal-Auth-Key", internalKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError,
                            (request, response) -> {
                                int status = response.getStatusCode().value();
                                if (status == 404) throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Không tìm thấy tài khoản");
                                if (status == 409) throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Không sửa tài khoản đã lưu trữ");
                                throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "Tài khoản không hoạt động");
                            })
                    .onStatus(HttpStatusCode::is5xxServerError,
                            (request, response) -> { throw new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Auth session validation is unavailable"); })
                    .toBodilessEntity();
        } catch (ResponseStatusException rejected) {
            throw rejected;
        } catch (RestClientResponseException responseFailure) {
            int status = responseFailure.getStatusCode().value();
            if (status == 404) throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Không tìm thấy tài khoản");
            if (status == 409) throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Không sửa tài khoản đã lưu trữ");
            if (status >= 400 && status < 500) throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "Tài khoản không hoạt động");
            throw new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Auth session validation failed");
        } catch (RestClientException unavailable) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Auth session validation is unavailable");
        }
    }

    private String currentToken() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) return null;
        Object principal = authentication.getPrincipal();
        if (principal instanceof Jwt jwt) return jwt.getTokenValue();
        // UsernamePasswordAuthenticationToken in tests stores credentials? Fall back to credentials.
        Object credentials = authentication.getCredentials();
        return credentials instanceof String s ? s : null;
    }
}
