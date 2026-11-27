package com.example.booking_service.config;

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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class InternalBookingAuthenticationFilter extends OncePerRequestFilter {

    private static final Pattern TICKET_MAIL = Pattern.compile("/internal/booking/bookings/[0-9]+/ticket-mail");
    private final BookingInternalSecurityProperties properties;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        String path = request.getServletPath();
        if (!path.startsWith("/internal/booking/")) {
            filterChain.doFilter(request, response);
            return;
        }

        boolean ticketOnly = "GET".equals(request.getMethod()) && TICKET_MAIL.matcher(path).matches();
        String internalKey = request.getHeader(ticketOnly ? "X-Internal-Notification-Key" : "X-Internal-Booking-Key");
        String expected = ticketOnly ? properties.getTicketMailKey() : properties.getInternalKey();
        if (internalKey == null || internalKey.isBlank() || expected == null || expected.isBlank()
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), internalKey.getBytes(StandardCharsets.UTF_8))) {
            log.warn("Invalid or missing internal key for path: {}", path);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"Invalid internal API key\"}");
            return;
        }

        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "internal-booking-service",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_INTERNAL-service"))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
        filterChain.doFilter(request, response);
    }
}