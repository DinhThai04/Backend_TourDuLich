package com.example.file_service.config;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Verifies the short-lived service authorization in addition to the caller's user permission. */
@Component
public class TourGalleryAuthorization {
    private final SecretKeySpec key;

    public TourGalleryAuthorization(@Value("${jwt.signerKey}") String signerKey) {
        key = new SecretKeySpec(signerKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public void require(String action, String tourId, String proof) {
        if (proof == null || !proof.matches("[0-9]{1,12}\\.[0-9a-f]{64}"))
            throw new AccessDeniedException("Thiếu xác nhận thao tác từ dịch vụ tour");
        int separator = proof.indexOf('.');
        long expiresAt = Long.parseLong(proof.substring(0, separator));
        long now = Instant.now().getEpochSecond();
        if (expiresAt < now || expiresAt > now + 120)
            throw new AccessDeniedException("Xác nhận thao tác ảnh đã hết hạn");
        String actor = SecurityContextHolder.getContext().getAuthentication().getName();
        String command = "sgu-tour-gallery-v1\n" + action + "\n" + tourId + "\n" + actor + "\n" + expiresAt;
        try {
            Mac mac = Mac.getInstance(key.getAlgorithm());
            mac.init(key);
            byte[] expected = mac.doFinal(command.getBytes(StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(expected, HexFormat.of().parseHex(proof.substring(separator + 1))))
                throw new AccessDeniedException("Xác nhận thao tác ảnh không hợp lệ");
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Cannot verify tour gallery command", failure);
        }
    }
}
