package com.example.tour_service.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The catalogue snapshot is a bulk feed for auth/search only, not a storefront read.
 * Require the shared internal key; without a configured key (local dev) accept loopback
 * callers only, so a client reaching the service port from the LAN or gateway cannot pull
 * the whole catalogue anonymously.
 */
@Component
@Order(Integer.MIN_VALUE + 100)
public class CatalogSnapshotAccessFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Catalog-Key";
    private final byte[] key;

    public CatalogSnapshotAccessFilter(@Value("${app.catalog.internal-key:}") String key) {
        this.key = key == null ? new byte[0] : key.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!request.getRequestURI().endsWith("/tours/search-snapshot")) {
            chain.doFilter(request, response);
            return;
        }
        String supplied = request.getHeader(HEADER);
        boolean allowed = key.length == 0
                ? isLoopback(request.getRemoteAddr())
                : supplied != null && MessageDigest.isEqual(key, supplied.getBytes(StandardCharsets.UTF_8));
        if (!allowed) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":1401,\"message\":\"Dữ liệu danh mục chỉ dùng nội bộ\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isLoopback(String remote) {
        return "127.0.0.1".equals(remote) || "::1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote);
    }
}
