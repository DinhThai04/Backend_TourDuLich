package com.example.tour_service.config;

import com.example.tour_service.dto.request.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Base64;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class InternalTourAuthenticationFilter extends OncePerRequestFilter {

    private final TourInternalSecurityProperties properties;
    private final ObjectMapper objectMapper;

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

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        String path = request.getServletPath();

        // Check if public endpoint (no auth required)
        if (isPublicEndpoint(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        // JWT validation
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            unauthenticated(response);
            return;
        }

        String token = authHeader.substring(7);
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
            log.warn("JWT validation failed: {}", e.getMessage());
            unauthenticated(response);
        }
    }

    private boolean isPublicEndpoint(String path) {
        return PUBLIC_GET.stream().anyMatch(path::matches);
    }

    private void unauthenticated(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.getHeaders().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        try {
            com.example.tour_service.dto.request.ApiResponse<?> apiResponse =
                    com.example.tour_service.dto.request.ApiResponse.builder()
                            .code(1401)
                            .message("Bạn chưa đăng nhập hoặc phiên đã hết hạn")
                            .build();
            response.getWriter().write(objectMapper.writeValueAsString(apiResponse));
            response.getWriter().flush();
        } catch (Exception e) {
            log.error("Failed to write unauthenticated response", e);
        }
    }
}
