package com.example.auth_service.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal session validation for services that must not read Auth-owned tables. */
@RestController
@RequestMapping("/internal/auth/sessions")
public class InternalSessionController {

    @PostMapping("/validate")
    public ResponseEntity<Void> validate(@AuthenticationPrincipal Jwt jwt) {
        // The resource-server decoder has already verified signature, expiry, token type,
        // account status, credential version, session epoch, and explicit revocation.
        return jwt == null ? ResponseEntity.status(401).build() : ResponseEntity.noContent().build();
    }
}
