package com.example.payment_service.repository.httpclient;

import com.example.payment_service.config.PricingFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@FeignClient(name = "pricing-service-internal", url = "${app.services.pricing-base-url:http://localhost:8084/pricing}", configuration = PricingFeignConfiguration.class)
public interface PricingInternalClient {

    @GetMapping("/internal/pricing/prices/{id}")
    Map<String, Object> getPriceById(@PathVariable("id") Long id);

    @PostMapping("/internal/pricing/prices/batch")
    List<Map<String, Object>> getPricesBatch(@RequestBody List<Long> ids);

    record InternalPriceResponse(
            Long id,
            BigDecimal adultPrice,
            BigDecimal childPrice,
            BigDecimal toddlerPrice,
            BigDecimal infantPrice,
            BigDecimal singleSupplementPrice
    ) {}
}