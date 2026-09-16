package com.example.api_gateway.config;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class AuthHeaderForwardFilter implements GlobalFilter, Ordered {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        HttpHeaders headers = exchange.getRequest().getHeaders();
        String authHeader = headers.getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader != null) {
            // Thêm header Authorization cho request downstream
            exchange = exchange.mutate()
                    .request(exchange.getRequest().mutate()
                            .header(HttpHeaders.AUTHORIZATION, authHeader)
                            .build())
                    .build();
        }

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return 0; // sau authentication filter
    }
}
