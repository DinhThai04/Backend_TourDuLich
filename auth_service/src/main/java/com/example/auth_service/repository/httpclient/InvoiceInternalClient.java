package com.example.auth_service.repository.httpclient;

import com.example.auth_service.config.InvoiceFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

@FeignClient(name = "invoice-service-internal", url = "${app.services.invoice-base-url:http://localhost:8084/invoice}", configuration = InvoiceFeignConfiguration.class)
public interface InvoiceInternalClient {

    @GetMapping("/internal/invoice/users/{accountId}/has-history")
    Map<String, Object> hasUserHistory(@PathVariable("accountId") Long accountId);
}
