package com.example.api_gateway;

import com.example.api_gateway.config.LoginRateLimitFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoginRateLimitTest {
    private final GatewayFilterChain chain = mock(GatewayFilterChain.class);
    private final String loginPath = "/api/v1/auth/users/login";

    LoginRateLimitTest() {
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    private LoginRateLimitFilter filter(int limit) {
        return filter(limit, 5, 10, 30);
    }

    private LoginRateLimitFilter filter(int loginLimit, int registerLimit, int googleLimit) {
        return filter(loginLimit, registerLimit, googleLimit, 30);
    }

    private LoginRateLimitFilter filter(int loginLimit, int registerLimit, int googleLimit, int refreshLimit) {
        return filter(loginLimit, registerLimit, googleLimit, refreshLimit, 10);
    }

    private LoginRateLimitFilter filter(int loginLimit, int registerLimit, int googleLimit,
                                        int refreshLimit, int passwordChangeLimit) {
        return new LoginRateLimitFilter(new ObjectMapper(), "/api/v1",
                loginLimit, registerLimit, googleLimit, refreshLimit, passwordChangeLimit, 60_000L);
    }

    private MockServerWebExchange loginFrom(String ip) {
        return loginFrom(ip, null, null);
    }

    private MockServerWebExchange loginFrom(String remoteIp, String realIp, String forwardedFor) {
        var request = MockServerHttpRequest.post(loginPath)
                .remoteAddress(new InetSocketAddress(remoteIp, 4444));
        if (realIp != null) request.header("X-Real-IP", realIp);
        if (forwardedFor != null) request.header("X-Forwarded-For", forwardedFor);
        return MockServerWebExchange.from(request);
    }

    private MockServerWebExchange post(String path, String ip) {
        return MockServerWebExchange.from(MockServerHttpRequest.post(path)
                .remoteAddress(new InetSocketAddress(ip, 4444)));
    }

    @Test void registerAndGoogleHaveIndependentBucketsFromLogin() {
        LoginRateLimitFilter filter = filter(20, 2, 3);
        String registerPath = "/api/v1/auth/users/register";
        String googlePath = "/api/v1/auth/users/google";
        String client = "10.0.0.9";

        for (int attempt = 1; attempt <= 2; attempt++) {
            MockServerWebExchange allowed = post(registerPath, client);
            filter.filter(allowed, chain).block();
            assertEquals(null, allowed.getResponse().getStatusCode());
        }
        MockServerWebExchange registerBlocked = post(registerPath, client);
        filter.filter(registerBlocked, chain).block();
        assertEquals(429, registerBlocked.getResponse().getStatusCode().value());

        // The same client can still log in and call Google sign-in: buckets are per endpoint.
        MockServerWebExchange login = post(loginPath, client);
        filter.filter(login, chain).block();
        assertEquals(null, login.getResponse().getStatusCode());

        for (int attempt = 1; attempt <= 3; attempt++) {
            MockServerWebExchange google = post(googlePath, client);
            filter.filter(google, chain).block();
            assertEquals(null, google.getResponse().getStatusCode());
        }
        MockServerWebExchange googleBlocked = post(googlePath, client);
        filter.filter(googleBlocked, chain).block();
        assertEquals(429, googleBlocked.getResponse().getStatusCode().value());
    }

    @Test void refreshIsThrottledOnItsOwnBucket() {
        LoginRateLimitFilter filter = filter(20, 5, 10, 2);
        String refreshPath = "/api/v1/auth/users/refresh";
        String client = "10.0.0.30";

        for (int attempt = 1; attempt <= 2; attempt++) {
            MockServerWebExchange allowed = post(refreshPath, client);
            filter.filter(allowed, chain).block();
            assertEquals(null, allowed.getResponse().getStatusCode());
        }
        MockServerWebExchange blocked = post(refreshPath, client);
        filter.filter(blocked, chain).block();
        assertEquals(429, blocked.getResponse().getStatusCode().value());

        // An exhausted refresh bucket must not lock the client out of logging in again.
        MockServerWebExchange login = post(loginPath, client);
        filter.filter(login, chain).block();
        assertEquals(null, login.getResponse().getStatusCode());
    }

    @Test void passwordChangeIsThrottledOnItsOwnBucket() {
        LoginRateLimitFilter filter = filter(20, 5, 10, 30, 2);
        String changePath = "/api/v1/auth/users/Change-password";
        String client = "10.0.0.40";

        for (int attempt = 1; attempt <= 2; attempt++) {
            MockServerWebExchange allowed = post(changePath, client);
            filter.filter(allowed, chain).block();
            assertEquals(null, allowed.getResponse().getStatusCode());
        }
        MockServerWebExchange blocked = post(changePath, client);
        filter.filter(blocked, chain).block();
        assertEquals(429, blocked.getResponse().getStatusCode().value());

        // Guessing the old password must not lock the account holder out of logging in.
        MockServerWebExchange login = post(loginPath, client);
        filter.filter(login, chain).block();
        assertEquals(null, login.getResponse().getStatusCode());
    }

    @Test void allowsUpToLimitThenReturns429() {
        LoginRateLimitFilter filter = filter(3);
        for (int attempt = 1; attempt <= 3; attempt++) {
            MockServerWebExchange exchange = loginFrom("10.0.0.1");
            filter.filter(exchange, chain).block();
            assertEquals(null, exchange.getResponse().getStatusCode());
        }
        MockServerWebExchange blocked = loginFrom("10.0.0.1");
        filter.filter(blocked, chain).block();
        assertEquals(429, blocked.getResponse().getStatusCode().value());
    }

    @Test void otherClientAndOtherPathAreNotAffected() {
        LoginRateLimitFilter filter = filter(1);
        filter.filter(loginFrom("10.0.0.1"), chain).block();
        MockServerWebExchange otherClient = loginFrom("10.0.0.2");
        filter.filter(otherClient, chain).block();
        assertEquals(null, otherClient.getResponse().getStatusCode());

        MockServerWebExchange otherPath = MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/v1/booking/book/create").remoteAddress(new InetSocketAddress("10.0.0.1", 4444)));
        filter.filter(otherPath, chain).block();
        assertEquals(null, otherPath.getResponse().getStatusCode());
    }

    @Test void publicClientsCannotChooseTheirOwnLimitKey() {
        LoginRateLimitFilter filter = filter(1);
        filter.filter(loginFrom("203.0.113.7", "198.51.100.1", null), chain).block();
        MockServerWebExchange second = loginFrom("203.0.113.7", "198.51.100.2", null);
        filter.filter(second, chain).block();
        assertEquals(429, second.getResponse().getStatusCode().value());
    }

    @Test void trustedProxyLimitsByRealClientAddress() {
        LoginRateLimitFilter filter = filter(1);
        filter.filter(loginFrom("10.0.0.5", "203.0.113.9", null), chain).block();
        MockServerWebExchange otherClient = loginFrom("10.0.0.5", "203.0.113.10", null);
        filter.filter(otherClient, chain).block();
        assertEquals(null, otherClient.getResponse().getStatusCode());

        MockServerWebExchange sameClient = loginFrom("10.0.0.5", "203.0.113.9", null);
        filter.filter(sameClient, chain).block();
        assertEquals(429, sameClient.getResponse().getStatusCode().value());
    }

    @Test void forwardedForFallsBackToTheRightmostValidHop() {
        LoginRateLimitFilter filter = filter(1);
        filter.filter(loginFrom("10.0.0.5", null, "spoofed, 203.0.113.20"), chain).block();
        MockServerWebExchange sameClient = loginFrom("10.0.0.5", null, "203.0.113.20");
        filter.filter(sameClient, chain).block();
        assertEquals(429, sameClient.getResponse().getStatusCode().value());
    }
}
