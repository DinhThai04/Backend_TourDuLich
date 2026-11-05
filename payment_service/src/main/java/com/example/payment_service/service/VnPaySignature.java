package com.example.payment_service.service;

import com.example.payment_service.config.VnPayConfig;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

final class VnPaySignature {
    private VnPaySignature() {
    }

    static String canonicalQuery(Map<String, String> fields) {
        // VNPay specifies UTF-8 for the signed query; ASCII only happened to work for ASCII-only order info.
        return new TreeMap<>(fields).entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().isEmpty())
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    static void verify(VnPayConfig config, Map<String, String> fields) {
        String received = fields.get("vnp_SecureHash");
        Map<String, String> signed = new HashMap<>(fields);
        signed.remove("vnp_SecureHash");
        signed.remove("vnp_SecureHashType");
        String expected = VnPayConfig.hmacSHA512(config.secretKey(), canonicalQuery(signed));
        if (received == null || received.length() != 128
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                        received.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII))) {
            throw new VnPayRejection("97", "Chữ ký VNPay không hợp lệ");
        }
        if (!Objects.equals(config.tmnCode(), fields.get("vnp_TmnCode"))) {
            throw new VnPayRejection("97", "Mã website không khớp");
        }
    }
}
