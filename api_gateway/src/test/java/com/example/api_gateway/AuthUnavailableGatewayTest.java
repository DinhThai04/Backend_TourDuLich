package com.example.api_gateway;

import com.example.api_gateway.config.AuthCircuitBreaker;
import com.example.api_gateway.config.AuthenticationFilter;
import com.example.api_gateway.config.IntrospectionCache;
import com.example.api_gateway.dto.response.ApiResponse;
import com.example.api_gateway.dto.response.IntrospectResponse;
import com.example.api_gateway.service.IdentityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.net.URI;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthUnavailableGatewayTest {
    private final IdentityService identity = mock(IdentityService.class);
    private final GatewayFilterChain chain = mock(GatewayFilterChain.class);

    private AuthenticationFilter filter() {
        return filter(new AuthCircuitBreaker(100, 10_000));
    }

    private AuthenticationFilter filter(AuthCircuitBreaker breaker) {
        AuthenticationFilter filter = new AuthenticationFilter(identity, new ObjectMapper(), breaker, new IntrospectionCache(0));
        ReflectionTestUtils.setField(filter, "apiPrefix", "/api/v1");
        when(chain.filter(any())).thenReturn(Mono.empty());
        return filter;
    }

    private MockServerWebExchange protectedRequest() {
        return MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/v1/booking/bookings/mine")
                .header(HttpHeaders.AUTHORIZATION, "Bearer some-token"));
    }

    private void authServiceDown() {
        when(identity.introspect(anyString())).thenReturn(Mono.error(new WebClientRequestException(
                new ConnectException("Connection refused"), HttpMethod.POST,
                URI.create("http://localhost:8080/auth/users/introspect"), new HttpHeaders())));
    }

    @Test void connectionFailureToAuthServiceReturns503Not401() {
        authServiceDown();

        MockServerWebExchange exchange = protectedRequest();
        filter().filter(exchange, chain).block();

        assertEquals(503, exchange.getResponse().getStatusCode().value());
    }

    @Test void timeoutToAuthServiceReturns503() {
        when(identity.introspect(anyString())).thenReturn(Mono.error(new WebClientRequestException(
                new TimeoutException("read timed out"), HttpMethod.POST,
                URI.create("http://localhost:8080/auth/users/introspect"), new HttpHeaders())));

        MockServerWebExchange exchange = protectedRequest();
        filter().filter(exchange, chain).block();

        assertEquals(503, exchange.getResponse().getStatusCode().value());
    }

    @Test void invalidTokenStillReturns401() {
        when(identity.introspect(anyString())).thenReturn(Mono.just(ApiResponse.<IntrospectResponse>builder()
                .code(1000)
                .result(IntrospectResponse.builder().valid(false).build())
                .build()));

        MockServerWebExchange exchange = protectedRequest();
        filter().filter(exchange, chain).block();

        assertEquals(401, exchange.getResponse().getStatusCode().value());
    }

    @Test void openCircuitStopsCallingAuthAfterThreshold() {
        authServiceDown();
        AuthenticationFilter filter = filter(new AuthCircuitBreaker(1, 60_000));

        MockServerWebExchange first = protectedRequest();
        filter.filter(first, chain).block();
        MockServerWebExchange second = protectedRequest();
        filter.filter(second, chain).block();

        assertEquals(503, first.getResponse().getStatusCode().value());
        assertEquals(503, second.getResponse().getStatusCode().value());
        verify(identity, times(1)).introspect(anyString());
    }

    @Test void unavailableResponseCarriesRetryAfter() {
        authServiceDown();

        MockServerWebExchange exchange = protectedRequest();
        AuthenticationFilter filter = filter();
        ReflectionTestUtils.setField(filter, "authBreakerOpenMs", 10_000L);
        filter.filter(exchange, chain).block();

        assertEquals("10", exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
    }

    @Test void downstreamFailureIsNeverBlamedOnTheAuthService() {
        when(identity.introspect(anyString())).thenReturn(Mono.just(ApiResponse.<IntrospectResponse>builder()
                .code(1000)
                .result(IntrospectResponse.builder().valid(true).build())
                .build()));
        AuthenticationFilter filter = filter(new AuthCircuitBreaker(1, 60_000));
        when(chain.filter(any())).thenReturn(Mono.error(new WebClientRequestException(
                new ConnectException("booking service is down"), HttpMethod.POST,
                URI.create("http://localhost:8081/booking/bookings/mine"), new HttpHeaders())));

        MockServerWebExchange exchange = protectedRequest();
        WebClientRequestException failure =
                assertThrows(WebClientRequestException.class, () -> filter.filter(exchange, chain).block());

        assertEquals("booking service is down", failure.getMessage());
        assertNull(exchange.getResponse().getStatusCode(), "the gateway must not answer for the routed service");

        // The breaker counted auth only, so the next request still reaches the auth service.
        when(chain.filter(any())).thenReturn(Mono.empty());
        MockServerWebExchange next = protectedRequest();
        filter.filter(next, chain).block();

        verify(identity, times(2)).introspect(anyString());
        verify(chain).filter(next);
    }

    @Test void emptyIntrospectionAnswerIsRejectedNotForwarded() {
        when(identity.introspect(anyString())).thenReturn(Mono.empty());

        MockServerWebExchange exchange = protectedRequest();
        filter().filter(exchange, chain).block();

        assertEquals(401, exchange.getResponse().getStatusCode().value());
        verify(chain, never()).filter(any());
    }
}

