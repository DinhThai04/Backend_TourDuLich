package com.example.auth_service.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class InternalAuthAuthenticationFilter extends OncePerRequestFilter {

    private final AuthInternalSecurityProperties properties;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        String path = request.getServletPath();
        if (!path.startsWith("/internal/auth/")) {
            filterChain.doFilter(request, response);
            return;
        }

        String internalKey = request.getHeader("X-Internal-Auth-Key");
        String expectedKey = properties.getInternalKey();
        if (internalKey == null || internalKey.isBlank() || expectedKey == null || expectedKey.isBlank()
                || !java.security.MessageDigest.isEqual(
                        internalKey.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        expectedKey.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            log.warn("Invalid or missing internal key for path: {}", path);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"Invalid internal API key\"}");
            return;
        }

        // Let the resource-server bearer filter authenticate the forwarded user token.
        // Other internal calls without a bearer token retain the internal service principal.
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    "internal-auth-service",
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_INTERNAL-service"))
            );
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        filterChain.doFilter(request, response);
    }
}
