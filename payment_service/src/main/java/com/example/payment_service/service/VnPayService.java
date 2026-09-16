package com.example.payment_service.service;


import com.example.payment_service.config.VnPayConfig;
import com.example.payment_service.dto.request.VnPayRequest;
import com.example.payment_service.dto.response.VnPayResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class VnPayService {
    /***
     * CREATE VNPAY PAYMENT
     * @param paymentRequest
     * @return
     * @throws UnsupportedEncodingException
     */
    public String createPayment(VnPayRequest paymentRequest) throws UnsupportedEncodingException {
        String vnp_Version = "2.1.0";
        String vnp_Command = "pay";
        String orderType = "other";
        String vnp_TxnRef = VnPayConfig.getRandomNumber(8);
        String vnp_IpAddr = "127.0.0.1";
        String vnp_TmnCode = VnPayConfig.vnp_TmnCode;
        String vnp_amount_STR = "";

        long vnp_amount = 0;
        try {
            vnp_amount = Long.parseLong(paymentRequest.getAmount());
            vnp_amount_STR = String.valueOf(vnp_amount);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Số tiền không hợp lệ");
        }

        Map<String, String> vnp_Params = new HashMap<>();
        vnp_Params.put("vnp_Version", vnp_Version);
        vnp_Params.put("vnp_Command", vnp_Command);
        vnp_Params.put("vnp_TmnCode", vnp_TmnCode);
        vnp_Params.put("vnp_Amount", vnp_amount_STR);
        vnp_Params.put("vnp_CurrCode", "VND");
        vnp_Params.put("vnp_TxnRef", vnp_TxnRef);
        vnp_Params.put("vnp_OrderInfo", "Thanh toan don hang:" + vnp_TxnRef);
        vnp_Params.put("vnp_OrderType", orderType);
        vnp_Params.put("vnp_Locale", "vn");
        vnp_Params.put("vnp_ReturnUrl", VnPayConfig.vnp_ReturnUrl);
        vnp_Params.put("vnp_IpAddr", vnp_IpAddr);

        Calendar cld = Calendar.getInstance(TimeZone.getTimeZone("Etc/GMT+7"));
        SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMddHHmmss");
        String vnp_CreateDate = formatter.format(cld.getTime());
        vnp_Params.put("vnp_CreateDate", vnp_CreateDate);

        cld.add(Calendar.MINUTE, 15);
        String vnp_ExpireDate = formatter.format(cld.getTime());
        vnp_Params.put("vnp_ExpireDate", vnp_ExpireDate);

        List<String> fieldNames = new ArrayList<>(vnp_Params.keySet());
        Collections.sort(fieldNames);
        StringBuilder hashData = new StringBuilder();
        StringBuilder query = new StringBuilder();
        for (String fieldName : fieldNames) {
            String fieldValue = vnp_Params.get(fieldName);
            if ((fieldValue != null) && (fieldValue.length() > 0)) {
                hashData.append(fieldName).append('=')
                        .append(URLEncoder.encode(fieldValue, StandardCharsets.US_ASCII.toString()));
                query.append(URLEncoder.encode(fieldName, StandardCharsets.US_ASCII.toString()))
                        .append('=')
                        .append(URLEncoder.encode(fieldValue, StandardCharsets.US_ASCII.toString()));
                query.append('&');
                hashData.append('&');
            }
        }

        if (query.length() > 0)
            query.setLength(query.length() - 1);
        if (hashData.length() > 0)
            hashData.setLength(hashData.length() - 1);

        String vnp_SecureHash = VnPayConfig.hmacSHA512(VnPayConfig.secretKey, hashData.toString());
        query.append("&vnp_SecureHash=").append(vnp_SecureHash);
        return VnPayConfig.vnp_PayUrl + "?" + query;
    }

    /**
     * CALLBACK PAYMENT
     * @param fields
     * @param request
     * @return
     */
    public VnPayResponse handlePaymentReturn(Map<String, String> fields, HttpServletRequest request) {
        Map<String, String> vnpParamsToHash = new HashMap<>(fields);

        List<String> fieldNames = new ArrayList<>(vnpParamsToHash.keySet());
        Collections.sort(fieldNames);

        StringBuilder hashData = new StringBuilder();
        for (String name : fieldNames) {
            hashData.append(name).append("=").append(fields.get(name));
            if (!name.equals(fieldNames.get(fieldNames.size() - 1))) {
                hashData.append("&");
            }
        }

        String txnRef = fields.get("vnp_TxnRef");
        String responseCode = fields.get("vnp_ResponseCode");
        String amount = fields.get("vnp_Amount");

        // Nếu giao dịch thành công → trả JSON
        if ("00".equals(responseCode)) {
            return VnPayResponse.builder()
                    .status("success")
                    .orderId(txnRef)
                    .amount(amount)
                    .bankCode(fields.get("vnp_BankCode"))
                    .transactionNo(fields.get("vnp_TransactionNo"))
                    .bankTranNo(fields.get("vnp_TransactionNo"))
                    .cardType(fields.get("vnp_CardType"))
                    .orderInfo(fields.get("vnp_OrderInfo"))
                    .payDate(fields.get("vnp_PayDate"))
                    .responseCode(fields.get("vnp_ResponseCode"))
                    .transactionStatus(fields.get("vnp_TransactionStatus"))
                    .txnRef(fields.get("vnp_TxnRef"))
                    .secureHash(fields.get("vnp_SecureHash"))
                    .build();
        }

        return VnPayResponse.builder()
                .status("failed")
                .orderId(txnRef)
                .amount(amount)
                .responseCode(responseCode)
                .orderInfo(fields.get("vnp_OrderInfo"))
                .transactionStatus(fields.get("vnp_TransactionStatus"))
                .build();
    }

}
