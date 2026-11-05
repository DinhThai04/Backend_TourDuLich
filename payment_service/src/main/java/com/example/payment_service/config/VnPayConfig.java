package com.example.payment_service.config;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

@org.springframework.stereotype.Component
public class VnPayConfig {

    private final String payUrl;
    private final String tmnCode;
    private final String returnUrl;
    private final String secretKey;

    @org.springframework.beans.factory.annotation.Autowired
    public VnPayConfig(
            @org.springframework.beans.factory.annotation.Value("${vnpay.secret-key}") String secretKey,
            @org.springframework.beans.factory.annotation.Value("${vnpay.tmn-code}") String tmnCode,
            @org.springframework.beans.factory.annotation.Value("${vnpay.return-url}") String returnUrl,
            @org.springframework.beans.factory.annotation.Value("${vnpay.pay-url}") String payUrl) {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException("VNPAY hash secret is required");
        }
        if (tmnCode == null || tmnCode.isBlank()) {
            throw new IllegalStateException("VNPAY tmn code is required");
        }
        this.secretKey = secretKey;
        this.tmnCode = tmnCode;
        this.returnUrl = returnUrl;
        this.payUrl = payUrl;
    }

    public String payUrl() {
        return payUrl;
    }

    public String tmnCode() {
        return tmnCode;
    }

    public String returnUrl() {
        return returnUrl;
    }

    public String secretKey() {
        return secretKey;
    }

    /** Fails loudly: a signing error must never degrade into an empty signature. */
    public static String hmacSHA512(final String key, final String data) {
        if (key == null || data == null) {
            throw new IllegalArgumentException("Missing key or data for HMAC");
        }
        try {
            final Mac hmac512 = Mac.getInstance("HmacSHA512");
            hmac512.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            byte[] result = hmac512.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(2 * result.length);
            for (byte b : result) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Cannot sign VNPay payload", failure);
        }
    }
}
