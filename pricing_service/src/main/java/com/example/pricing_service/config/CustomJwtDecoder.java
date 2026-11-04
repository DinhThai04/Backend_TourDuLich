package com.example.pricing_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/** Verify signature and timestamps even when the service is reached without the gateway. */
@Component
public class CustomJwtDecoder implements JwtDecoder {
    private final NimbusJwtDecoder delegate;
    private final TokenRevocationChecker revocation;
    @org.springframework.beans.factory.annotation.Autowired
    public CustomJwtDecoder(@Value("${jwt.signerKey}") String key, TokenRevocationChecker revocation) {
        delegate = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA512"))
                .macAlgorithm(MacAlgorithm.HS512).build();
        this.revocation = revocation;
    }
    CustomJwtDecoder(String key) { this(key, null); }
    @Override
    public Jwt decode(String token) throws JwtException {
        Jwt jwt = delegate.decode(token);
        // A refresh token carries the same signature; it must never authenticate API calls.
        if ("refresh".equalsIgnoreCase(jwt.getClaimAsString("tokenType")))
            throw new BadJwtException("Refresh token cannot be used as an access token");
        if (revocation != null) revocation.verify(jwt);
        return jwt;
    }
}
