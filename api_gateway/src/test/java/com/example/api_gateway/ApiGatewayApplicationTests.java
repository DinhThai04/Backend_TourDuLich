package com.example.api_gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"MAIL_INTERNAL_API_KEY=test-mail-key"})
class ApiGatewayApplicationTests {

    @Autowired RouteLocator routes;

    @Test void rewritesConfirmationToBookingOwner() {
        var route = routes.getRoutes().filter(candidate -> candidate.getId().equals("confirm-departure")).blockFirst();
        assertNotNull(route);
        var request = MockServerHttpRequest.post("/api/v1/tour/departures/42/confirm-departure").build();
        var exchange = MockServerWebExchange.from(request);
        assertEquals(Boolean.TRUE, Mono.from(route.getPredicate().apply(exchange)).block());
        route.getFilters().getFirst().filter(exchange, forwarded -> {
            assertEquals("/booking/operations/departures/42/confirm-departure", forwarded.getRequest().getPath().value());
            return Mono.empty();
        }).block();
    }

	@Test
	void contextLoads() {
	}

}
