package com.example.auth_service.config;

import com.example.auth_service.entity.User;
import com.example.auth_service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Account management uses current database permissions, never client-provided roles. */
@Component("accountAccess")
@RequiredArgsConstructor
public class AccountAccess {
    private final UserRepository users;

    public boolean owns(Object id) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || id == null || !auth.getName().equals(id.toString())) return false;
        try { return users.findById(Long.parseLong(auth.getName())).map(u -> u.getStatus() == com.example.auth_service.entity.UserStatus.ACTIVE).orElse(false); }
        catch (NumberFormatException invalid) { return false; }
    }

    @Transactional(readOnly = true)
    public boolean can(String code) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return false;
        User user;
        try { user = users.findById(Long.parseLong(auth.getName())).orElse(null); }
        catch (NumberFormatException e) { return false; }
        if (user == null || user.getStatus() != com.example.auth_service.entity.UserStatus.ACTIVE || user.getRole() == null || !Boolean.TRUE.equals(user.getRole().getActive())) return false;
        if ("admin".equalsIgnoreCase(user.getRole().getName())) return true;
        if (user.getRole().getPermissions() == null) return false;
        return user.getRole().getPermissions().stream().anyMatch(p ->
                p != null && p.getCode() != null && code.equalsIgnoreCase(p.getCode()));
    }
}
