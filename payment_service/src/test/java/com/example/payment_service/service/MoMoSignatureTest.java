package com.example.payment_service.service;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MoMoSignatureTest {
    @Test void hmacMatchesRfc4231TestVector() {
        // RFC 4231 test case 2: key "Jefe", data "what do ya want for nothing?".
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
                MoMoSignature.hmacSHA256("Jefe", "what do ya want for nothing?"));
    }

    @Test void createSignatureUsesFixedFieldOrderAndRawValues() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("partnerCode", "MOMOPARTNER");
        params.put("accessKey", "ACCESS");
        params.put("requestId", "req-1");
        params.put("amount", "10000");
        params.put("orderId", "order-1");
        params.put("orderInfo", "Thanh toan booking 1");
        params.put("redirectUrl", "http://localhost/cb");
        params.put("ipnUrl", "http://localhost/ipn");
        params.put("requestType", "captureWallet");
        params.put("extraData", "");
        String signature = MoMoSignature.signCreate("secret", params);
        // Rebuild the raw string the exact documented way and sign it independently.
        String raw = "accessKey=" + params.get("accessKey")
                + "&amount=" + params.get("amount")
                + "&extraData=" + params.get("extraData")
                + "&ipnUrl=" + params.get("ipnUrl")
                + "&orderId=" + params.get("orderId")
                + "&orderInfo=" + params.get("orderInfo")
                + "&partnerCode=" + params.get("partnerCode")
                + "&redirectUrl=" + params.get("redirectUrl")
                + "&requestId=" + params.get("requestId")
                + "&requestType=" + params.get("requestType");
        assertEquals(MoMoSignature.hmacSHA256("secret", raw), signature);
    }

    @Test void callbackSignatureUsesDocumentedOrderAndConfiguredAccessKey() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("partnerCode", "MOMOPARTNER");
        fields.put("orderId", "order-1");
        fields.put("requestId", "req-1");
        fields.put("amount", "10000");
        fields.put("orderInfo", "Thanh toan booking 1");
        fields.put("orderType", "momo_wallet");
        fields.put("payType", "qr");
        fields.put("responseTime", "1700000000000");
        fields.put("resultCode", "0");
        fields.put("transId", "123");
        fields.put("extraData", "");
        fields.put("message", "Success");
        String raw = "accessKey=ACCESS"
                + "&amount=" + fields.get("amount")
                + "&extraData="
                + "&message=Success"
                + "&orderId=order-1"
                + "&orderInfo=" + fields.get("orderInfo")
                + "&orderType=momo_wallet"
                + "&partnerCode=MOMOPARTNER"
                + "&payType=qr"
                + "&requestId=req-1"
                + "&responseTime=1700000000000"
                + "&resultCode=0"
                + "&transId=123";
        fields.put("signature", MoMoSignature.hmacSHA256("secret", raw));

        assertDoesNotThrow(() -> MoMoSignature.verify("ACCESS", "secret", fields));
        assertThrows(VnPayRejection.class, () -> MoMoSignature.verify("OTHER", "secret", fields));

        fields.put("amount", "9999");
        assertThrows(VnPayRejection.class, () -> MoMoSignature.verify("ACCESS", "secret", fields));
        fields.put("amount", "10000");
        fields.remove("signature");
        assertThrows(VnPayRejection.class, () -> MoMoSignature.verify("ACCESS", "secret", fields));
    }

    @Test void createResponsePayUrlMustStayOnTheConfiguredGatewayHost() {
        String configured = "https://test-payment.momo.vn/v2/gateway/api/create";
        assertTrue(MoMoSignature.trustedPayUrl(configured,
                "https://test-payment.momo.vn/v2/gateway/pay?t=abc"));
        assertFalse(MoMoSignature.trustedPayUrl(configured, "https://attacker.example/pay"));
        assertFalse(MoMoSignature.trustedPayUrl(configured, "http://test-payment.momo.vn/pay"));
        assertFalse(MoMoSignature.trustedPayUrl(configured, "not a uri"));
        assertFalse(MoMoSignature.trustedPayUrl(configured, null));
    }
}
