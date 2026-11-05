package com.example.booking_service.config;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;
import java.time.Instant;
import java.util.Date;
import static org.junit.jupiter.api.Assertions.*;

class JwtSignatureTest {
    private final String key = "test-only-key-that-is-long-enough-for-hs512-012345678901234567890123456789";
    private String token(String signer, Instant expiry) throws Exception {
        var token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), new JWTClaimsSet.Builder()
                .subject("7").issueTime(Date.from(Instant.now().minusSeconds(120))).expirationTime(Date.from(expiry)).build());
        token.sign(new MACSigner(signer.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return token.serialize();
    }
    @Test void verifiesSignatureInsteadOfOnlyParsingClaims() throws Exception {
        var decoder = new CustomJwtDecoder(key);
        assertEquals("7", decoder.decode(token(key, Instant.now().plusSeconds(300))).getSubject());
        String forged = token(key.replace('0','x'), Instant.now().plusSeconds(300));
        assertThrows(JwtException.class, () -> decoder.decode(forged));
    }
    @Test void rejectsExpiredToken() throws Exception {
        String expired = token(key, Instant.now().minusSeconds(120));
        assertThrows(JwtException.class, () -> new CustomJwtDecoder(key).decode(expired));
    }
}

