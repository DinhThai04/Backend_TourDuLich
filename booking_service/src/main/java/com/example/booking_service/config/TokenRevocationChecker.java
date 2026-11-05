package com.example.booking_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;

/**
 * Signature checks alone cannot see a logout, a locked account or a password change,
 * so this service re-checks the session against the shared auth tables. One query
 * keeps the cost per authenticated request bounded.
 */
@Component
public class TokenRevocationChecker {
    private static final String LOOKUP = """
            SELECT u.status, u.password,
                   COALESCE((SELECT MAX(e.id) FROM user_account_event e WHERE e.target_user_id=u.id),0) AS epoch,
                   (SELECT COUNT(*) FROM invalidated_token t WHERE t.id=?) AS revoked
            FROM users u WHERE u.id=?
            """;

    private final JdbcTemplate jdbc;
    private final String signerKey;

    public TokenRevocationChecker(JdbcTemplate jdbc, @Value("${jwt.signerKey}") String signerKey) {
        this.jdbc = jdbc;
        this.signerKey = signerKey;
    }

    public void verify(Jwt jwt) {
        Long id;
        try {
            id = Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException | NullPointerException invalid) {
            throw new BadJwtException("Token subject is invalid");
        }
        String jti = jwt.getId() == null ? "" : jwt.getId();
        List<SessionRow> rows = jdbc.query(LOOKUP,
                (rs, index) -> new SessionRow(rs.getString("status"), rs.getString("password"),
                        rs.getLong("epoch"), rs.getLong("revoked")), jti, id);
        if (rows.isEmpty() || !"ACTIVE".equals(rows.getFirst().status()) || rows.getFirst().revoked() > 0)
            throw new BadJwtException("Session is no longer valid");

        SessionRow row = rows.getFirst();
        Object rawEpoch = jwt.getClaim("sessionEpoch");
        long epoch = rawEpoch instanceof Number number ? number.longValue() : 0L;
        String binding = jwt.getClaimAsString("credentialVersion");
        if (epoch != row.epoch() || binding == null
                || !MessageDigest.isEqual(binding.getBytes(StandardCharsets.UTF_8),
                        credentialVersion(id, row.password()).getBytes(StandardCharsets.UTF_8)))
            throw new BadJwtException("Session was revoked");
    }

    private String credentialVersion(Long id, String password) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signerKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String value = id + ":" + (password == null ? "" : password);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Cannot bind session credentials", exception);
        }
    }

    private record SessionRow(String status, String password, long epoch, long revoked) {}
}
