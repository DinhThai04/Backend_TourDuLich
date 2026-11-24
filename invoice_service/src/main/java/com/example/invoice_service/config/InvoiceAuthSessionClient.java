package com.example.invoice_service.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Calls Auth's session boundary instead of querying Auth-owned tables from Invoice. */
@Component
public class InvoiceAuthSessionClient {
    private final RestClient client;
    private final String internalKey;

    public InvoiceAuthSessionClient(
            @Value("${app.auth.base-url:http://localhost:8080/auth}") String baseUrl,
            @Value("${AUTH_INTERNAL_KEY:}") String internalKey,
            @Value("${app.auth.connect-timeout:2s}") Duration connectTimeout,
            @Value("${app.auth.read-timeout:3s}") Duration readTimeout) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
        this.internalKey = internalKey;
    }

    public void requireActiveSession(Jwt jwt) {
        if (jwt == null || jwt.getTokenValue() == null || jwt.getTokenValue().isBlank()) {
            throw new BadJwtException("Session token is missing");
        }
        if (internalKey == null || internalKey.isBlank()) {
            throw new JwtException("Auth internal API key is not configured");
        }
        try {
            client.post()
                    .uri("/internal/auth/sessions/validate")
                    .header("Authorization", "Bearer " + jwt.getTokenValue())
                    .header("X-Internal-Auth-Key", internalKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError,
                            (request, response) -> { throw new BadJwtException("Session is no longer valid"); })
                    .onStatus(HttpStatusCode::is5xxServerError,
                            (request, response) -> { throw new JwtException("Auth session validation is unavailable"); })
                    .toBodilessEntity();
        } catch (JwtException rejected) {
            throw rejected;
        } catch (RestClientResponseException responseFailure) {
            if (responseFailure.getStatusCode().is4xxClientError()) {
                throw new BadJwtException("Session is no longer valid");
            }
            throw new JwtException("Auth session validation failed", responseFailure);
        } catch (RestClientException unavailable) {
            throw new JwtException("Auth session validation is unavailable", unavailable);
        }
    }
}
