package com.example.tour_service.config;

import java.io.IOException;
import java.util.UUID;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestTracingFilter extends OncePerRequestFilter {
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(RequestTracingFilter.class);
    private static final String HEADER = "X-Request-ID";

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String inbound = request.getHeader(HEADER);
        String id = inbound != null && inbound.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")
                ? inbound : UUID.randomUUID().toString();
        long started = System.nanoTime();
        response.setHeader(HEADER, id);
        var previous = MDC.get("requestId");
        MDC.put("requestId", id);
        try {
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException failure) {
            LOG.warn("request_id={} error={}", id, failure.getClass().getSimpleName());
            throw failure;
        } finally {
            long duration = (System.nanoTime() - started) / 1_000_000;
            if (response.getStatus() >= 400 || duration >= 1000)
                LOG.info("request_id={} handler={} status={} duration_ms={}", id,
                        request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE), response.getStatus(), duration);
            if (previous == null) MDC.remove("requestId");
            else MDC.put("requestId", previous);
        }
    }
}
