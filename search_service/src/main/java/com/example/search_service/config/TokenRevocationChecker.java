package com.example.search_service.config;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Search has no database connection, so it asks the auth service whether a token
 * was revoked. Only protected writes reach this check; public reads stay offline.
 */
@Component
public class TokenRevocationChecker {
    private final RestClient client;
    private final String introspectUrl;

    public TokenRevocationChecker(RestClient.Builder builder,
            @Value("${app.auth.introspect-url:http://localhost:8080/auth/users/introspect}") String introspectUrl) {
        // Bounded waits: a hung auth service must fail the request instead of pinning a thread.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(2_000);
        requestFactory.setReadTimeout(3_000);
        this.client = builder.requestFactory(requestFactory).build();
        this.introspectUrl = introspectUrl;
    }

    public void verify(String token) {
        JsonNode body;
        try {
            body = client.post().uri(introspectUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("token", token))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RuntimeException unavailable) {
            // An auth outage must not look like a valid session.
            throw new BadJwtException("Cannot verify token revocation with the auth service");
        }
        if (body == null || body.path("code").asInt() != 1000 || !body.path("result").path("valid").asBoolean())
            throw new BadJwtException("Session is no longer valid");
    }
}
