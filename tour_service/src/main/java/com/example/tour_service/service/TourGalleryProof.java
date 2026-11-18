package com.example.tour_service.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Authorizes the initial gallery write on a tour created by this service. */
@Component
public class TourGalleryProof {
    private final SecretKeySpec key;

    public TourGalleryProof(@Value("${jwt.signerKey}") String signerKey) {
        key = new SecretKeySpec(signerKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public String issue(String action, int tourId) {
        String actor = SecurityContextHolder.getContext().getAuthentication().getName();
        long expiresAt = Instant.now().plusSeconds(120).getEpochSecond();
        String command = "sgu-tour-gallery-v1\n" + action + "\n" + tourId + "\n" + actor + "\n" + expiresAt;
        try {
            Mac mac = Mac.getInstance(key.getAlgorithm());
            mac.init(key);
            return expiresAt + "." + HexFormat.of().formatHex(mac.doFinal(command.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Cannot authorize tour gallery command", failure);
        }
    }
}
