package com.example.booking_service.notification;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/** Registered ONLY inside the exact internal endpoint's security chain. */
public class InternalMailAuthenticationFilter extends OncePerRequestFilter {
    private final String key;
    public InternalMailAuthenticationFilter(String key) { this.key=key; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        String supplied=request.getHeader("X-Internal-Mail-Key");
        if(key==null || key.length()<32) { reject(response,503,"Internal mail API is not configured"); return; }
        if(supplied==null || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8))) {
            reject(response,401,"Invalid internal mail credential");return;
        }
        if(!"POST".equals(request.getMethod())) { reject(response,405,"Only POST is supported");return; }
        var authentication=new UsernamePasswordAuthenticationToken("n8n-internal",null,
                List.of(new SimpleGrantedAuthority("INTERNAL_MAIL_DISPATCH")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        chain.doFilter(request,response);
    }
    private void reject(HttpServletResponse response,int status,String message) throws IOException {
        response.setStatus(status);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":1401,\"message\":\""+message+"\"}");
    }
}
