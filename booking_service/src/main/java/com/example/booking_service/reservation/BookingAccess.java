package com.example.booking_service.reservation;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Component
public class BookingAccess {
    public int accountId() {
        Integer id = currentAccountId();
        if (id == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập");
        return id;
    }
    /** Null when the caller is a background job instead of an authenticated request. */
    public Integer currentAccountId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken token) || !token.isAuthenticated()) return null;
        try { return Integer.parseInt(token.getToken().getSubject()); }
        catch (RuntimeException invalid) { return null; }
    }
    public boolean admin() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> "SGU_SUPER_ADMIN".equals(a.getAuthority()));
    }
    public boolean can(String permissionCode) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return admin() || auth != null && auth.isAuthenticated() && auth.getAuthorities().stream()
                .anyMatch(a -> ("PERM:" + permissionCode).equals(a.getAuthority()));
    }
    public void requireReadOwner(int owner, String permissionCode) {
        if (accountId() != owner && !can(permissionCode))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn chưa được cấp quyền đọc dữ liệu này");
    }
    public void requireOwner(int owner) {
        if (accountId() != owner && !admin())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Booking không thuộc tài khoản đăng nhập");
    }
}
