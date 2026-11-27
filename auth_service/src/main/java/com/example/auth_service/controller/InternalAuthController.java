package com.example.auth_service.controller;

import com.example.auth_service.dto.internal.InternalUserResponse;
import com.example.auth_service.service.InternalAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/internal/auth/users")
@RequiredArgsConstructor
public class InternalAuthController {
    private final InternalAccountService accounts;

    @GetMapping("/{id}")
    public ResponseEntity<InternalUserResponse> getUserById(@PathVariable Long id) {
        return ResponseEntity.of(accounts.findUser(id));
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<InternalAccountService.AccountStatus> getUserStatus(@PathVariable Long id) {
        return ResponseEntity.of(accounts.findStatus(id));
    }

    @PostMapping("/batch")
    public ResponseEntity<List<InternalUserResponse>> getUsersBatch(@RequestBody List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 100 || ids.stream().anyMatch(id -> id == null || id < 1)) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(accounts.findUsers(ids));
    }

    @PostMapping("/{ownerId}/avatar-write-permission")
    public ResponseEntity<Void> checkAvatarWritePermission(
            @PathVariable Long ownerId,
            @RequestParam(defaultValue = "false") boolean lockTarget,
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication) {
        return ResponseEntity.status(accounts.avatarWritePermission(ownerId, lockTarget, jwt, authentication)).build();
    }
}
