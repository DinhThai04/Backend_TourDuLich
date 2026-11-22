package com.example.payment_service.repository.httpclient;

import com.example.payment_service.config.AuthFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@FeignClient(name = "auth-service-internal", url = "${app.services.auth-base-url:http://auth-service:8080/auth}", configuration = AuthFeignConfiguration.class)
public interface AuthInternalClient {

    @GetMapping("/internal/auth/users/{id}")
    Map<String, Object> getUserById(@PathVariable("id") Long id);

    @GetMapping("/internal/auth/users/{id}/status")
    Map<String, Object> getUserStatus(@PathVariable("id") Long id);

    @PostMapping("/internal/auth/users/batch")
    List<Map<String, Object>> getUsersBatch(@RequestBody List<Long> ids);
}