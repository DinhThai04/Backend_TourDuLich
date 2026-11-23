package com.example.tour_service.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestTracingFilter extends OncePerRequestFilter {
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(RequestTracingFilter.class);
    public static final String HEADER = "X-Request-ID";

    @Value("${app.api-prefix}")
    private String apiPrefix;
    @Value("${jwt.signerKey}")
    private String signerKey;

    private static final List<String> PUBLIC_GET = List.of(
            "/tours/list", "/tours/random-3", "/tours/search",
            "/tours/search-snapshot", "/tour/tours/search-snapshot",
            "/tour/tours/**",
            "/tours/{id:[0-9]+}",
            "/vehicles/list",
            "/locations/all",
            "/locations/departures",
            "/locations/destinations",
            "/locations/departures/search",
            "/locations/destinations/search",
            "/locations/destinations/attractive",
            "/tour-departures/tour/{tourId:[0-9]+}",
            "/tour-departures/{id:[0-9]+}",
            "/itineraries/tour/{tourId:[0-9]+}"
        );

    private static final List<String> PUBLIC_POST = List.of(
            "/auth/users/login", "/auth/users/logout", "/auth/users/google",
            "/auth/users/register", "/auth/users/refresh", "/pricing/prices/batch",
            "/payment/momo/ipn");

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws jakarta.servlet.ServletException, java.io.IOException {
        String id = UUID.randomUUID().toString();
        long started = System.nanoTime();
        response.setHeader(HEADER, id);
        var previous = MDC.get("requestId");
        MDC.put("requestId", id);
        try {
            String path = request.getServletPath();
            if (!isPublicEndpoint(path, request.getMethod())) {
                authenticate(request, response, chain, id, started);
                return;
            }
            chain.doFilter(request, response);
        } catch (jakarta.servlet.ServletException | java.io.IOException | RuntimeException failure) {
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

    private boolean isPublicEndpoint(String path, HttpServletRequest request) {
        LOG.info("RTF_IS_PUBLIC: path={} method={} apiPrefix={}", path, request.getMethod(), apiPrefix);
        if ("GET".equals(request.getMethod()) && (apiPrefix + "/ai/policies").equals(path)) return true;
        if ("POST".equals(request.getMethod()) && (apiPrefix + "/ai/booking-help").equals(path)
                && !request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) return true;
        String relative = path.startsWith(apiPrefix) ? path.substring(apiPrefix.length()) : path;
        LOG.info("RTF_IS_PUBLIC: relative={}", relative);
        boolean result = PUBLIC_GET.stream().anyMatch(relative::matches) || PUBLIC_POST.stream().anyMatch(relative::matches);
        LOG.info("RTF_IS_PUBLIC: result={}", result);
        return result;
    }

    private void authenticate(HttpServletRequest request, HttpServletResponse response, FilterChain chain, String id, long started)
            throws jakarta.servlet.ServletException, java.io.IOException {
        List<String> authHeader = CollectionUtils.isEmpty(request.getHeaders().get(HttpHeaders.AUTHORIZATION))
                ? null : request.getHeaders().get(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || authHeader.size() != 1 || !authHeader.getFirst().startsWith("Bearer ") || authHeader.getFirst().length() <= 7) {
            unauthenticated(response);
            return;
        }

        String token = authHeader.getFirst().substring(7);

        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                unauthenticated(response);
                return;
            }
            String signingPart = parts[0] + "." + parts[1];
            String signaturePart = parts[2];
            byte[] signature = Base64.getUrlDecoder().decode(signaturePart);
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(signerKey.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(signingPart.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (!java.security.MessageDigest.isEqual(signature, expected)) {
                unauthenticated(response);
                return;
            }
            chain.doFilter(request, response);
        } catch (Exception e) {
            LOG.warn("JWT validation failed: {}", e.getMessage());
            unauthenticated(response);
        }
    }

    private void unauthenticated(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED);
        response.getHeaders().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        try {
            String json = "{\"code\":1401,\"message\":\"Bạn chưa đăng nhập hoặc phiên đã hết hạn\"}";
            response.getWriter().write(json);
            response.getWriter().flush();
        } catch (Exception e) {
            LOG.error("Failed to write unauthenticated response", e);
        }
    }
}
