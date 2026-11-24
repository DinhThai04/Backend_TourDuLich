package com.example.tour_service.repository.httpclient;

import com.example.tour_service.config.PricingFeignConfiguration;
import com.example.tour_service.dto.internal.InternalPriceResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@FeignClient(name = "pricing-service-internal", url = "${app.services.pricing-base-url:http://pricing-service:8084/pricing}", configuration = PricingFeignConfiguration.class)
public interface PricingInternalClient {

    @GetMapping("/internal/pricing/prices/{id}")
    Map<String, Object> getPriceById(@PathVariable("id") Long id);

    @PostMapping("/internal/pricing/prices/batch")
    List<InternalPriceResponse> getPricesBatch(@RequestBody List<Long> ids);
}
