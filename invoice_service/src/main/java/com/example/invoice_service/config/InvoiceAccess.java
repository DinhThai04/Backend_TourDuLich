package com.example.invoice_service.config;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;

public final class InvoiceAccess {
    private InvoiceAccess() {}
    public static int accountId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) throw new AccessDeniedException("Cần đăng nhập");
        try { return Integer.parseInt(auth.getName()); }
        catch (NumberFormatException ex) { throw new AccessDeniedException("Tài khoản không hợp lệ"); }
    }
    public static boolean admin() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && auth.getAuthorities().stream()
                .anyMatch(a -> "SGU_SUPER_ADMIN".equals(a.getAuthority()));
    }
    public static void requireOwner(int owner) {
        if (!admin() && accountId() != owner) throw new AccessDeniedException("Không được xem hóa đơn của tài khoản khác");
    }
    /** Cash-desk staff holding INVOICES_CREATE may bill a booking they do not own. */
    public static void requireCreateOwner(int owner) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        boolean delegated = auth != null && auth.isAuthenticated() && auth.getAuthorities().stream()
                .anyMatch(a -> "PERM:INVOICES_CREATE".equals(a.getAuthority()));
        if (!admin() && !delegated && accountId() != owner)
            throw new AccessDeniedException("Không được tạo hóa đơn cho booking của tài khoản khác");
    }
    public static boolean canReadAll() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return admin() || auth != null && auth.isAuthenticated() && auth.getAuthorities().stream()
                .anyMatch(a -> "PERM:INVOICES_GET_ALL".equals(a.getAuthority()));
    }
    public static void requireReadOwner(int owner, String permissionCode) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        boolean delegated = auth != null && auth.isAuthenticated() && auth.getAuthorities().stream()
                .anyMatch(a -> ("PERM:" + permissionCode).equals(a.getAuthority()));
        if (!canReadAll() && !delegated && accountId() != owner) throw new AccessDeniedException("Không được xem hóa đơn của tài khoản khác");
    }
}
