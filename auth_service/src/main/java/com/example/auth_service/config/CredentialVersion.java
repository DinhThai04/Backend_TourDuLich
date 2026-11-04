package com.example.auth_service.config;

import com.example.auth_service.entity.User;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Opaque credential binding; never expose the stored password hash in a JWT. */
public final class CredentialVersion {
    private CredentialVersion() {}
    public static String of(User user, String signerKey) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signerKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String value = user.getId() + ":" + java.util.Objects.toString(user.getPassword(), "");
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("Cannot bind session credentials", exception);
        }
    }
}
