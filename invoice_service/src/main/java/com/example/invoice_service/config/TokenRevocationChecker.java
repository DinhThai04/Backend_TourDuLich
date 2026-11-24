package com.example.invoice_service.config;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Delegates live session state to Auth, which owns account and revocation data. */
@Component
public class TokenRevocationChecker {
    private final InvoiceAuthSessionClient authSessionClient;

    public TokenRevocationChecker(InvoiceAuthSessionClient authSessionClient) {
        this.authSessionClient = authSessionClient;
    }

    public void verify(Jwt jwt) {
        authSessionClient.requireActiveSession(jwt);
    }
}
