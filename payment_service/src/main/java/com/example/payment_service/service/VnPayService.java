package com.example.payment_service.service;

import com.example.payment_service.config.VnPayConfig;
import com.example.payment_service.dto.request.VnPayRequest;
import com.example.payment_service.dto.response.BookingQuoteResponse;
import com.example.payment_service.dto.response.VnPayResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
public class VnPayService {
    private final VnPayOrderService orders;
    private final VnPayConfig config;

    public static String canonicalQuery(Map<String, String> fields) {
        return VnPaySignature.canonicalQuery(fields);
    }

    public String createPayment(VnPayRequest request, int accountId, HttpServletRequest httpRequest) {
        if (request.getBookingId() == null || request.getBookingId() <= 0)
            throw new IllegalArgumentException("Thiếu bookingId hợp lệ");
        return buildPaymentUrl(request, accountId, httpRequest);
    }

    /** Giá phải trả do server tính, dùng cho màn hình thanh toán. */
    public BookingQuoteResponse quote(int bookingId, int accountId) {
        if (bookingId <= 0) throw new IllegalArgumentException("Thiếu bookingId hợp lệ");
        var quote = orders.quote(bookingId, accountId, "vnpay");
        return BookingQuoteResponse.builder()
            .amount(Long.toString(quote.amountVnd()))
            .unitPrices(quote.unitPrices())
            .build();
    }

    private String buildPaymentUrl(VnPayRequest request, int accountId, HttpServletRequest httpRequest) {
        var order = orders.prepare(request.getBookingId(), accountId, request.getExpectedAmount());
        var formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
        Map<String, String> params = new HashMap<>();
        params.put("vnp_Version", "2.1.0");
        params.put("vnp_Command", "pay");
        params.put("vnp_TmnCode", config.tmnCode());
        params.put("vnp_Amount", Long.toString(Math.multiplyExact(order.amountVnd(), 100L)));
        params.put("vnp_CurrCode", "VND");
        params.put("vnp_TxnRef", order.txnRef());
        params.put("vnp_OrderInfo", "Thanh toan booking " + request.getBookingId());
        params.put("vnp_OrderType", "other");
        params.put("vnp_Locale", "vn");
        params.put("vnp_ReturnUrl", config.returnUrl());
        params.put("vnp_IpAddr", clientIp(httpRequest));
        params.put("vnp_CreateDate", formatter.format(order.createdAt()));
        params.put("vnp_ExpireDate", formatter.format(order.expiresAt()));
        params.put("vnp_SecureHashType", "SHA512");
        String query = canonicalQuery(params);
        return config.payUrl() + "?" + query + "&vnp_SecureHash="
            + VnPayConfig.hmacSHA512(config.secretKey(), query);
    }

    /** VNPay rejects non-IPv4 and gateway addresses; recover the customer's real IPv4 where trusted. */
    static String clientIp(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        boolean localPeer = remote == null || isLocalAddress(remote);
        if (localPeer) {
            // nginx overwrites X-Real-IP with the browser address and the gateway forwards it
            // untouched. X-Forwarded-For is only the fallback: with nginx -> gateway -> payment two
            // hops are appended, so its rightmost entry is a proxy address, not the customer.
            String realIp = request.getHeader("X-Real-IP");
            if (realIp != null && isIpv4(realIp.trim())) return realIp.trim();
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null) {
                String[] hops = forwarded.split(",");
                String privateHop = null;
                // Right to left: a client can invent hops on the left, but the proxy hops on the
                // right were appended by our own chain, so the first customer-side IPv4 wins.
                for (int i = hops.length - 1; i >= 0; i--) {
                    String candidate = hops[i].trim();
                    if (!isIpv4(candidate)) continue;
                    if (!isLocalAddress(candidate)) return candidate;
                    // An all-private chain (LAN development) still beats the loopback proxy address.
                    privateHop = candidate;
                }
                if (privateHop != null) return privateHop;
            }
        }
        return isIpv4(remote) ? remote : "127.0.0.1";
    }

    private static boolean isLocalAddress(String ip) {
        if (ip == null) return true;
        if ("::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)) return true;
        int[] octets = ipv4(ip);
        if (octets == null) return false;
        return octets[0] == 127 || octets[0] == 10
                || (octets[0] == 192 && octets[1] == 168)
                || (octets[0] == 169 && octets[1] == 254)
                || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31);
    }

    private static boolean isIpv4(String value) {
        return ipv4(value) != null;
    }

    /** Parses a dotted-quad address, rejecting out-of-range octets and non-numeric parts. */
    private static int[] ipv4(String value) {
        if (value == null || value.isBlank()) return null;
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return null;
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 3) return null;
            int octet = 0;
            for (char c : parts[i].toCharArray()) {
                if (c < '0' || c > '9') return null;
                octet = octet * 10 + (c - '0');
            }
            if (octet > 255) return null;
            octets[i] = octet;
        }
        return octets;
    }

    public void verifySignature(Map<String, String> fields) {
        VnPaySignature.verify(config, fields);
    }

    public VnPayResponse handlePaymentReturn(Map<String, String> fields) {
        verifySignature(fields);
        String status = orders.settle(fields);
        return VnPayResponse.builder()
            .status("duplicate".equals(status) ? "success" : status).alreadyProcessed("duplicate".equals(status)).orderId(fields.get("vnp_TxnRef"))
            .amount(new java.math.BigDecimal(fields.get("vnp_Amount")).movePointLeft(2).toPlainString())
            .bankCode(fields.get("vnp_BankCode"))
            .transactionNo(fields.get("vnp_TransactionNo"))
            .bankTranNo(fields.get("vnp_BankTranNo"))
            .cardType(fields.get("vnp_CardType"))
            .orderInfo(fields.get("vnp_OrderInfo"))
            .payDate(fields.get("vnp_PayDate"))
            .responseCode(fields.get("vnp_ResponseCode"))
            .transactionStatus(fields.get("vnp_TransactionStatus"))
            .txnRef(fields.get("vnp_TxnRef")).build();
    }
}
