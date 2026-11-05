package com.example.booking_service.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TokenRevocationCheckerTest {
    private static final String KEY = "test-only-key-that-is-long-enough-for-hs512-012345678901234567890123456789";
    private JdbcTemplate jdbc;
    private TokenRevocationChecker checker;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:revocation-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, status VARCHAR(20), password VARCHAR(255))");
        jdbc.execute("CREATE TABLE user_account_event (id BIGINT AUTO_INCREMENT PRIMARY KEY, target_user_id BIGINT)");
        jdbc.execute("CREATE TABLE invalidated_token (id VARCHAR(255) PRIMARY KEY)");
        jdbc.update("INSERT INTO users VALUES (7,'ACTIVE','hash-1')");
        checker = new TokenRevocationChecker(jdbc, KEY);
    }

    @Test
    void acceptsActiveSessionWithCurrentBinding() {
        assertDoesNotThrow(() -> checker.verify(token("hash-1", 0L, "jti-1")));
    }

    @Test
    void rejectsLockedAccountPasswordChangeOrBlacklistedToken() {
        jdbc.update("UPDATE users SET status='LOCKED' WHERE id=7");
        assertThrows(JwtException.class, () -> checker.verify(token("hash-1", 0L, "jti-1")));
        jdbc.update("UPDATE users SET status='ACTIVE', password='hash-2' WHERE id=7");
        assertThrows(JwtException.class, () -> checker.verify(token("hash-1", 0L, "jti-1")));
        jdbc.update("UPDATE users SET password='hash-1' WHERE id=7");
        jdbc.update("INSERT INTO invalidated_token VALUES ('jti-1')");
        assertThrows(JwtException.class, () -> checker.verify(token("hash-1", 0L, "jti-1")));
    }

    @Test
    void rejectsTokenIssuedBeforeTheLatestAccountEvent() {
        jdbc.update("INSERT INTO user_account_event (target_user_id) VALUES (7)");
        assertThrows(JwtException.class, () -> checker.verify(token("hash-1", 0L, "jti-2")));
        // A newer token (issued after the event) stays valid.
        assertDoesNotThrow(() -> checker.verify(token("hash-1", 1L, "jti-3")));
    }

    private Jwt token(String password, long epoch, String jti) {
        return Jwt.withTokenValue("token")
                .header("alg", "HS512")
                .subject("7")
                .claim("sessionEpoch", epoch)
                .claim("credentialVersion", binding(password))
                .issuedAt(Instant.now().minusSeconds(60))
                .expiresAt(Instant.now().plusSeconds(600))
                .claim("jti", jti)
                .build();
    }

    private String binding(String password) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(("7:" + password).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
