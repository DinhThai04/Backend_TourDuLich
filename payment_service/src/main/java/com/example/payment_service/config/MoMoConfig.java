package com.example.payment_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * MoMo AIO v2 credentials. Unlike VNPay, MoMo is optional: until test credentials are supplied the
 * checkout returns a clear "not configured" error instead of failing the whole service startup.
 */
@Component
public class MoMoConfig {
    private final String partnerCode;
    private final String accessKey;
    private final String secretKey;
    private final String payUrl;
    private final String returnUrl;
    private final String ipnUrl;

    public MoMoConfig(
            @Value("${momo.partner-code:}") String partnerCode,
            @Value("${momo.access-key:}") String accessKey,
            @Value("${momo.secret-key:}") String secretKey,
            @Value("${momo.pay-url:https://test-payment.momo.vn/v2/gateway/api/create}") String payUrl,
            @Value("${momo.return-url:http://localhost:5173/payment/momo/callback}") String returnUrl,
            @Value("${momo.ipn-url:http://localhost:8083/payment/momo/ipn}") String ipnUrl) {
        this.partnerCode = partnerCode;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.payUrl = payUrl;
        this.returnUrl = returnUrl;
        this.ipnUrl = ipnUrl;
    }

    public boolean isConfigured() {
        return !isBlank(partnerCode) && !isBlank(accessKey) && !isBlank(secretKey);
    }

    public String partnerCode() { return partnerCode; }
    public String accessKey() { return accessKey; }
    public String secretKey() { return secretKey; }
    public String payUrl() { return payUrl; }
    public String returnUrl() { return returnUrl; }
    public String ipnUrl() { return ipnUrl; }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
