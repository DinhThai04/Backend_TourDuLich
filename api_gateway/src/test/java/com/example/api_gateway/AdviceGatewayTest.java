package com.example.api_gateway;

import com.example.api_gateway.config.AuthCircuitBreaker;
import com.example.api_gateway.config.AuthenticationFilter;
import com.example.api_gateway.config.IntrospectionCache;
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

class AdviceGatewayTest {
    @Test void onlyExactReadMethodsBypassLogin() {
        var identity=mock(IdentityService.class);
        var filter=new AuthenticationFilter(identity,new ObjectMapper(),new AuthCircuitBreaker(100,10_000),new IntrospectionCache(0));
        ReflectionTestUtils.setField(filter,"apiPrefix","/api/v1");
        var chain=mock(GatewayFilterChain.class);when(chain.filter(any())).thenReturn(Mono.empty());
        for(var request:new MockServerHttpRequest[]{
                MockServerHttpRequest.post("/api/v1/tour/advice/search").build(),
                MockServerHttpRequest.get("/api/v1/tour/advice/locations").build()}) {
            var exchange=MockServerWebExchange.from(request);filter.filter(exchange,chain).block();verify(chain).filter(exchange);
        }
        for(var request:new MockServerHttpRequest[]{
                MockServerHttpRequest.get("/api/v1/tour/advice/search").build(),
                MockServerHttpRequest.post("/api/v1/tour/advice/locations").build(),
                MockServerHttpRequest.post("/api/v1/tour/advice/search/extra").build(),
                MockServerHttpRequest.delete("/api/v1/tour/advice/search").build()}) {
            var exchange=MockServerWebExchange.from(request);filter.filter(exchange,chain).block();
            assertEquals(401,exchange.getResponse().getStatusCode().value());verify(chain,never()).filter(exchange);
        }
        verifyNoInteractions(identity);
    }
}
