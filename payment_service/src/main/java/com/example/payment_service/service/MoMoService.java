package com.example.payment_service.service;

import com.example.payment_service.config.MoMoConfig;
import com.example.payment_service.dto.response.BookingQuoteResponse;
import com.example.payment_service.dto.response.MoMoResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import java.time.Clock;
import java.util.*;

@Service
@RequiredArgsConstructor
public class MoMoService {
    // An unresponsive provider must not pin request threads: bound connect and read time.
    private static final RestClient HTTP = RestClient.builder().requestFactory(timeoutFactory()).build();

    private static SimpleClientHttpRequestFactory timeoutFactory() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        return factory;
    }

    private final MoMoOrderService orders;
    private final VnPayOrderService sharedQuote;
    private final MoMoConfig config;
    private final ObjectMapper mapper;
    /** {@code expires_at} is Vietnam wall-clock, so the cache must convert with the same zone. */
    private final Clock clock;

    /** A reused PENDING order must return its already-created payUrl instead of asking MoMo twice. */
    private final Map<String, CachedPayUrl> payUrls = new java.util.concurrent.ConcurrentHashMap<>();
    /** Hard ceiling: failed return visits never remove entries, so the cache needs its own bound. */
    private static final int MAX_CACHED_PAY_URLS = 2_000;
    private record CachedPayUrl(String url, java.time.Instant expiresAt) {}

    private void rememberPayUrl(String orderId, CachedPayUrl cached) {
        if (payUrls.size() >= MAX_CACHED_PAY_URLS) {
            java.time.Instant now = java.time.Instant.now();
            payUrls.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
            // Still full after pruning: drop one entry so new orders stay cacheable and memory is bounded.
            if (payUrls.size() >= MAX_CACHED_PAY_URLS) payUrls.keySet().stream().findFirst().ifPresent(payUrls::remove);
        }
        payUrls.put(orderId, cached);
    }

    public BookingQuoteResponse quote(int bookingId, int accountId) {
        var quote = sharedQuote.quote(bookingId, accountId, "momo");
        return BookingQuoteResponse.builder()
                .amount(Long.toString(quote.amountVnd()))
                .unitPrices(quote.unitPrices())
                .build();
    }

    /** Returns the MoMo payUrl, or 503 until test credentials are configured. */
    public String createPayment(int bookingId, String expectedAmount, int accountId) {
        if (!config.isConfigured())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "MoMo chưa được cấu hình; vui lòng cấu hình tài khoản test MoMo");
        var order = orders.prepare(bookingId, accountId, expectedAmount);
        if (order.reused()) {
            var cached = payUrls.get(order.orderId());
            if (cached != null && cached.expiresAt().isAfter(java.time.Instant.now())) return cached.url();
            // The original URL was never captured (e.g. service restart). Close that row so
            // MoMo does not reject a duplicate orderId, then create a fresh one.
            payUrls.remove(order.orderId());
            orders.abandon(order.orderId());
            order = orders.prepare(bookingId, accountId, expectedAmount);
        }
        Map<String, String> body = new LinkedHashMap<>();
        body.put("partnerCode", config.partnerCode());
        body.put("accessKey", config.accessKey());
        body.put("requestId", order.requestId());
        body.put("amount", Long.toString(order.amountVnd()));
        body.put("orderId", order.orderId());
        body.put("orderInfo", "Thanh toan booking " + bookingId);
        body.put("redirectUrl", config.returnUrl());
        body.put("ipnUrl", config.ipnUrl());
        body.put("requestType", "captureWallet");
        body.put("extraData", "");
        body.put("lang", "vi");
        body.put("signature", MoMoSignature.signCreate(config.secretKey(), body));

        Map<?, ?> result;
        try {
            result = post(config.payUrl(), body);
            int resultCode = resultCode(result.get("resultCode"));
            if (resultCode != 0)
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "MoMo từ chối thanh toán: " + result.get("message"));
            Object payUrl = result.get("payUrl");
            // MoMo does not sign the create response; the gateway host is the trust anchor.
            if (!MoMoSignature.trustedPayUrl(config.payUrl(), payUrl))
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "MoMo không trả về liên kết thanh toán hợp lệ");
            String url = payUrl.toString();
            // Retries while the order is still PENDING must navigate to the same checkout.
            rememberPayUrl(order.orderId(), new CachedPayUrl(url,
                    order.expiresAt().atZone(clock.getZone()).toInstant()));
            return url;
        } catch (RuntimeException failure) {
            // Only a row created by this call may be abandoned; a reused PENDING order is still payable.
            if (!order.reused()) {
                payUrls.remove(order.orderId());
                orders.abandon(order.orderId());
            }
            throw failure;
        }
    }

    private static int resultCode(Object value) {
        if (value == null) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "MoMo không trả về mã kết quả");
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "MoMo trả về mã kết quả không hợp lệ");
        }
    }

    public MoMoResponse handlePaymentReturn(Map<String, String> fields) {
        MoMoSignature.verify(config.accessKey(), config.secretKey(), fields);
        String status = orders.settle(fields);
        return MoMoResponse.builder()
                .status("duplicate".equals(status) ? "success" : status)
                .alreadyProcessed("duplicate".equals(status))
                .orderId(fields.get("orderId"))
                .requestId(fields.get("requestId"))
                .amount(fields.get("amount"))
                .transId(fields.get("transId"))
                .resultCode(fields.get("resultCode"))
                .message(fields.get("message"))
                .build();
    }

    private Map<?, ?> post(String url, Map<String, String> body) {
        try {
            String raw = HTTP.post().uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(body))
                    .retrieve().body(String.class);
            if (raw == null || raw.isBlank()) throw new IllegalStateException("MoMo trả về phản hồi rỗng");
            return mapper.readValue(raw, Map.class);
        } catch (Exception failure) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Không kết nối được tới MoMo", failure);
        }
    }
}
