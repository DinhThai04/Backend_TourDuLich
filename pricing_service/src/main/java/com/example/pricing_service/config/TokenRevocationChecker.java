package com.example.pricing_service.config;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Delegates live session state to Auth, which owns account and revocation data. */
@Component
public class TokenRevocationChecker {
    private final PricingAuthSessionClient authSessionClient;

    public TokenRevocationChecker(PricingAuthSessionClient authSessionClient) {
        this.authSessionClient = authSessionClient;
    }

    public void verify(Jwt jwt) {
        authSessionClient.requireActiveSession(jwt);
    }
}
