package com.example.api_gateway.config;

import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class RequestTracingFilter implements GlobalFilter, Ordered {
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(RequestTracingFilter.class);
    public static final String HEADER = "X-Request-ID";

    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }

    @Override public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String id = UUID.randomUUID().toString();
        long started = System.nanoTime();
        var request = exchange.getRequest().mutate().headers(headers -> headers.set(HEADER, id)).build();
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(HEADER, id);
            return Mono.empty();
        });
        return chain.filter(exchange.mutate().request(request).build()).doFinally(signal -> {
            var status = exchange.getResponse().getStatusCode();
            long duration = (System.nanoTime() - started) / 1_000_000;
            if (status == null || status.isError() || duration >= 1000) {
                var route = exchange.<org.springframework.cloud.gateway.route.Route>getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
                LOG.info("api request_id={} route={} status={} duration_ms={}", id,
                        route == null ? "-" : route.getId(), status, duration);
            }
        });
    }
}
