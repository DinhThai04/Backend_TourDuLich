package com.example.api_gateway;

import com.example.api_gateway.config.AuthCircuitBreaker;
import com.example.api_gateway.config.AuthenticationFilter;
import com.example.api_gateway.config.IntrospectionCache;
import com.example.api_gateway.service.IdentityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InternalMailGatewayTest {
    String path="/api/v1/booking/internal/mail/reminders/dispatch";
    String key="test-only-internal-key-0123456789abcdef";
    IdentityService identity=mock(IdentityService.class);
    GatewayFilterChain chain=mock(GatewayFilterChain.class);
    AuthenticationFilter filter() {
        var filter=new AuthenticationFilter(identity,new ObjectMapper(),new AuthCircuitBreaker(100,10_000),new IntrospectionCache(0));
        ReflectionTestUtils.setField(filter,"apiPrefix","/api/v1");ReflectionTestUtils.setField(filter,"internalMailKey",key);
        when(chain.filter(any())).thenReturn(Mono.empty());return filter;
    }
    @Test void acceptsCorrectKeyWithoutAuthServiceAndForwardsHeader() {
        var exchange=MockServerWebExchange.from(MockServerHttpRequest.post(path).header("X-Internal-Mail-Key",key));
        filter().filter(exchange,chain).block();verify(chain).filter(exchange);verifyNoInteractions(identity);
        assertEquals(key,exchange.getRequest().getHeaders().getFirst("X-Internal-Mail-Key"));
    }
    @Test void rejectsMissingWrongAndBearerOnly() {
        for(String supplied:new String[]{"","wrong"}) {
            var exchange=MockServerWebExchange.from(MockServerHttpRequest.post(path).header("X-Internal-Mail-Key",supplied).header("Authorization","Bearer admin-token"));
            filter().filter(exchange,chain).block();assertEquals(401,exchange.getResponse().getStatusCode().value());
        }
        verifyNoInteractions(identity);verify(chain,never()).filter(any());
    }
    @Test void disabledKeyFailsClosedAndWrongVerbIsRejected() {
        var filter=filter();ReflectionTestUtils.setField(filter,"internalMailKey","");
        var disabled=MockServerWebExchange.from(MockServerHttpRequest.post(path).header("X-Internal-Mail-Key",key));
        filter.filter(disabled,chain).block();assertEquals(503,disabled.getResponse().getStatusCode().value());
        var get=MockServerWebExchange.from(MockServerHttpRequest.get(path).header("X-Internal-Mail-Key",key));
        filter().filter(get,chain).block();assertEquals(405,get.getResponse().getStatusCode().value());
    }
    @Test void internalKeyDoesNotOpenOtherBookingEndpoints() {
        var exchange=MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/booking/book/create").header("X-Internal-Mail-Key",key));
        filter().filter(exchange,chain).block();assertEquals(401,exchange.getResponse().getStatusCode().value());
        verifyNoInteractions(identity);verify(chain,never()).filter(any());
    }
}
