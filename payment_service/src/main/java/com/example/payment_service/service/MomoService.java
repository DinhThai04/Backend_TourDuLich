package com.example.payment_service.service;

import com.example.payment_service.client.MomoApi;
import com.example.payment_service.dto.response.CreateMomoResponse;
import com.example.payment_service.dto.request.CreateMomoRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.example.payment_service.constant.MomoParameter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class MomoService {

    @Value("${momo.partner-code}")
    private String PARTNER_CODE;

    @Value("${momo.access-key}")
    private String ACCESS_KEY;

    @Value("${momo.secret-key}")
    private String SECRET_KEY;

    @Value("${momo.return-url}") // URL người dùng được redirect sau khi thanh toán xong
    private String REDIRECT_URL;

    @Value("${momo.ipn-url}") // URL backend nhận IPN
    private String IPN_URL;

    @Value("${momo.request-type}") // Loại giao dịch: thanh toán ví MoMo
    private String REQUEST_TYPE;

    private final MomoApi momoApi;

    public CreateMomoResponse createMomoQR(String generated_orderId,
                                           String generated_orderInfo,
                                           String generated_requestId,
                                           String generated_extraData,
                                           long calculated_amount) {
//        public CreateMomoResponse createMomoQR(String orderId, String orderInfo, String requestId, String extraData, String amount) {
        String orderId = generated_orderId;
        String orderInfo = generated_orderInfo;
        String requestId = generated_requestId;
        String extraData = generated_extraData;
        long amount = calculated_amount;

        String rawSignature = "accessKey=" + ACCESS_KEY +
                "&amount=" + amount +
                "&extraData=" + extraData +
                "&ipnUrl=" + IPN_URL +
                "&orderId=" + orderId +
                "&orderInfo=" + orderInfo +
                "&partnerCode=" + PARTNER_CODE +
                "&redirectUrl=" + REDIRECT_URL +
                "&requestId=" + requestId +
                "&requestType=" + REQUEST_TYPE;

        String prettySignature = "";
        try {
            prettySignature = signHmacSHA256(rawSignature, SECRET_KEY);
        } catch (Exception e) {
            log.error(e.getMessage());
            return null;
        }

        if (prettySignature.isBlank()) {
            log.error("Signature is blank !");
        }

        CreateMomoRequest req = CreateMomoRequest.builder()
                .partnerCode(PARTNER_CODE)
                .requestId(requestId)
                .amount(amount)
                .orderId(orderId)
                .orderInfo(orderInfo)
                .redirectUrl(REDIRECT_URL)
                .ipnUrl(IPN_URL)
                .requestType(REQUEST_TYPE)
                .extraData(extraData)
                .signature(prettySignature)
                .build();
        return momoApi.createMomoQr(req);
    }

    private String signHmacSHA256(String data, String key) throws Exception {
        Mac hmacSHA256 = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKey = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        hmacSHA256.init(secretKey);
        byte[] hash = hmacSHA256.doFinal(data.getBytes(StandardCharsets.UTF_8));

        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1)
                hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
