package com.example.tour_service.config;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;
import java.util.Date;
import static org.junit.jupiter.api.Assertions.*;
class JwtVerificationTest {
    private static final String KEY = "k".repeat(64);
    private String token(String key, long expiry) throws Exception {
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), new JWTClaimsSet.Builder()
            .subject("2").issueTime(new Date(System.currentTimeMillis()-120000))
            .expirationTime(new Date(expiry)).claim("scope", "SGU_SUPER_ADMIN").build());
        jwt.sign(new MACSigner(key)); return jwt.serialize();
    }
    @Test void rejectsForgedAdminToken() throws Exception {
        String forged = token("x".repeat(64), System.currentTimeMillis()+60000);
        assertThrows(JwtException.class, () -> new CustomJwtDecoder(KEY).decode(forged));
    }
    @Test void rejectsExpiredAndMalformedTokens() throws Exception {
        String expired = token(KEY, System.currentTimeMillis()-120000);
        var decoder = new CustomJwtDecoder(KEY);
        assertThrows(JwtException.class, () -> decoder.decode(expired));
        assertThrows(JwtException.class, () -> decoder.decode("invalid"));
    }
    @Test void acceptsValidSignedToken() throws Exception {
        assertEquals("2", new CustomJwtDecoder(KEY).decode(token(KEY, System.currentTimeMillis()+60000)).getSubject());
    }
}
