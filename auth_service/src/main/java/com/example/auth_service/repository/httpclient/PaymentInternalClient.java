package com.example.auth_service.repository.httpclient;

import com.example.auth_service.config.PaymentFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@FeignClient(name = "payment-service-internal", url = "${app.services.payment-base-url:http://localhost:8083/payment}", configuration = PaymentFeignConfiguration.class)
public interface PaymentInternalClient {

    @GetMapping("/internal/payment/reconciliation/open-count/{accountId}")
    Map<String, Object> getOpenReconciliationCount(@PathVariable("accountId") Long accountId);

    @GetMapping("/internal/payment/users/{accountId}/pending-transactions")
    Map<String, Object> getPendingTransactions(@PathVariable("accountId") Long accountId);

    @GetMapping("/internal/payment/users/{accountId}/has-history")
    Map<String, Object> hasUserHistory(@PathVariable("accountId") Long accountId);
}