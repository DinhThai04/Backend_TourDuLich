package com.example.tour_service.config;

import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Delegates live session state to Auth, which owns account and revocation data. */
@Component
public class TokenRevocationChecker {
    private final TourAuthSessionClient authSessionClient;

    public TokenRevocationChecker(TourAuthSessionClient authSessionClient) {
        this.authSessionClient = authSessionClient;
    }

    public void verify(Jwt jwt) {
        authSessionClient.requireActiveSession(jwt);
    }
}