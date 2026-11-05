package com.example.api_gateway;

import com.example.api_gateway.config.AuthCircuitBreaker;
import com.example.api_gateway.config.AuthenticationFilter;
import com.example.api_gateway.config.IntrospectionCache;
import com.example.api_gateway.dto.response.*;
import com.example.api_gateway.service.IdentityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManagementGatewayTest {
    @Test void catalogReadsArePublicButMutationsRequireIntrospection() {
        var identity = mock(IdentityService.class);
        var filter = new AuthenticationFilter(identity, new ObjectMapper(), new AuthCircuitBreaker(100, 10_000), new IntrospectionCache(0));
        ReflectionTestUtils.setField(filter, "apiPrefix", "/api/v1");
        var chain = mock(GatewayFilterChain.class); when(chain.filter(any())).thenReturn(Mono.empty());
        for (var request : new MockServerHttpRequest[]{
                MockServerHttpRequest.get("/api/v1/tour/tours/list").build(),
                MockServerHttpRequest.get("/api/v1/tour/tours/1").build(),
                MockServerHttpRequest.post("/api/v1/auth/users/login").build(),
                MockServerHttpRequest.post("/api/v1/auth/users/refresh").build(),
                MockServerHttpRequest.post("/api/v1/pricing/prices/batch").build()}) {
            var exchange = MockServerWebExchange.from(request); filter.filter(exchange, chain).block();
            verify(chain).filter(exchange);
        }
        for (var request : new MockServerHttpRequest[]{
                MockServerHttpRequest.put("/api/v1/tour/tours/1").build(),
                MockServerHttpRequest.delete("/api/v1/tour/tours/1").build(),
                MockServerHttpRequest.get("/api/v1/pricing/prices").build(),
                MockServerHttpRequest.get("/api/v1/tour/tour-departures/list").build(),
                MockServerHttpRequest.post("/api/v1/pricing/prices").build(),
                MockServerHttpRequest.post("/api/v1/search/tours").build()}) {
            var exchange = MockServerWebExchange.from(request); filter.filter(exchange, chain).block();
            assertEquals(401, exchange.getResponse().getStatusCode().value()); verify(chain, never()).filter(exchange);
        }
        verifyNoInteractions(identity);
    }
    @Test void introspectionIsNeverRoutedFromTheEdge() {
        var identity = mock(IdentityService.class);
        var filter = new AuthenticationFilter(identity, new ObjectMapper(), new AuthCircuitBreaker(100, 10_000), new IntrospectionCache(0));
        ReflectionTestUtils.setField(filter, "apiPrefix", "/api/v1");
        var chain = mock(GatewayFilterChain.class);
        for (var request : new MockServerHttpRequest[]{
                MockServerHttpRequest.post("/api/v1/auth/users/introspect").build(),
                MockServerHttpRequest.post("/api/v1/auth/users/introspect").header("Authorization", "Bearer any-token").build()}) {
            var exchange = MockServerWebExchange.from(request);
            filter.filter(exchange, chain).block();
            assertEquals(403, exchange.getResponse().getStatusCode().value());
            assertTrue(exchange.getResponse().getBodyAsString().block().contains("\"code\":1403"));
        }
        verifyNoInteractions(identity);
        verifyNoInteractions(chain);
    }
    @Test void capacitySummaryCannotBypassAuthenticationThroughPublicCatalogWildcard() {
        var identity=mock(IdentityService.class);
        when(identity.introspect("revoked")).thenReturn(Mono.just(ApiResponse.<IntrospectResponse>builder().result(IntrospectResponse.builder().valid(false).build()).build()));
        var filter=new AuthenticationFilter(identity,new ObjectMapper(),new AuthCircuitBreaker(100,10_000),new IntrospectionCache(0));ReflectionTestUtils.setField(filter,"apiPrefix","/api/v1");
        var chain=mock(GatewayFilterChain.class);
        for(var request:new MockServerHttpRequest[]{MockServerHttpRequest.get("/api/v1/tour/tour-departures/1/capacity").build(),
                MockServerHttpRequest.get("/api/v1/tour/tour-departures/1/capacity").header("Authorization","Bearer revoked").build()}) {
            var exchange=MockServerWebExchange.from(request);filter.filter(exchange,chain).block();assertEquals(401,exchange.getResponse().getStatusCode().value());
        }
        verify(identity).introspect("revoked");verifyNoInteractions(chain);
    }
    @Test void revokedAdminTokenCannotUseFormerlyPublicTourWritePath() {
        var identity = mock(IdentityService.class);
        when(identity.introspect("revoked-admin")).thenReturn(Mono.just(ApiResponse.<IntrospectResponse>builder()
                .result(IntrospectResponse.builder().valid(false).build()).build()));
        var filter = new AuthenticationFilter(identity, new ObjectMapper(), new AuthCircuitBreaker(100, 10_000), new IntrospectionCache(0));
        ReflectionTestUtils.setField(filter, "apiPrefix", "/api/v1");
        var chain = mock(GatewayFilterChain.class);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.put("/api/v1/tour/tours/1")
                .header("Authorization", "Bearer revoked-admin").build());
        filter.filter(exchange, chain).block();
        assertEquals(401, exchange.getResponse().getStatusCode().value()); verifyNoInteractions(chain);
        verify(identity).introspect("revoked-admin");
    }
}
