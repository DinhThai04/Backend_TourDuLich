package com.example.payment_service.service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * MoMo AIO v2 signatures. The create request and the IPN/return callback each sign a fixed list of
 * fields in a documented order. MoMo does not sign the create response, so its {@code payUrl} is
 * trusted only after the host is checked against the configured gateway.
 */
final class MoMoSignature {
    private static final List<String> CREATE_FIELDS = List.of(
            "accessKey", "amount", "extraData", "ipnUrl", "orderId", "orderInfo",
            "partnerCode", "redirectUrl", "requestId", "requestType");
    /** IPN/return order published by MoMo; {@code accessKey} comes from configuration. */
    private static final List<String> CALLBACK_FIELDS = List.of(
            "accessKey", "amount", "extraData", "message", "orderId", "orderInfo", "orderType",
            "partnerCode", "payType", "requestId", "responseTime", "resultCode", "transId");

    private MoMoSignature() {
    }

    static String signCreate(String secretKey, Map<String, String> params) {
        String raw = CREATE_FIELDS.stream()
                .map(field -> field + "=" + params.getOrDefault(field, ""))
                .collect(Collectors.joining("&"));
        return hmacSHA256(secretKey, raw);
    }

    static void verify(String accessKey, String secretKey, Map<String, String> fields) {
        if (!matches(signCallback(accessKey, secretKey, fields), fields.get("signature"))) {
            throw new VnPayRejection("97", "Chữ ký MoMo không hợp lệ");
        }
    }

    static String signCallback(String accessKey, String secretKey, Map<String, String> fields) {
        String raw = CALLBACK_FIELDS.stream()
                .map(field -> field + "=" + ("accessKey".equals(field)
                        ? accessKey == null ? "" : accessKey
                        : fields.getOrDefault(field, "")))
                .collect(Collectors.joining("&"));
        return hmacSHA256(secretKey, raw);
    }

    /**
     * A create response carries no signature, so only a payUrl on the configured MoMo gateway host
     * (over HTTPS) may be handed to the browser.
     */
    static boolean trustedPayUrl(String configuredCreateUrl, Object responsePayUrl) {
        if (responsePayUrl == null) return false;
        try {
            URI configured = URI.create(configuredCreateUrl);
            URI pay = URI.create(responsePayUrl.toString());
            return "https".equalsIgnoreCase(pay.getScheme())
                    && pay.getHost() != null
                    && pay.getHost().equalsIgnoreCase(configured.getHost());
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean matches(String expected, String received) {
        return received != null && expected.length() == received.length()
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                        received.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    static String hmacSHA256(String key, String data) {
        if (key == null || data == null) throw new IllegalArgumentException("Missing key or data for HMAC");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] result = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(result.length * 2);
            for (byte b : result) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Cannot sign MoMo payload", failure);
        }
    }
}
