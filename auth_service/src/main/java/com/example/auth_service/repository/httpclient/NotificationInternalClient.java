package com.example.auth_service.repository.httpclient;

import com.example.auth_service.config.NotificationFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

@FeignClient(name = "notification-service-internal", url = "${app.services.notification-base-url:http://localhost:8090}", configuration = NotificationFeignConfiguration.class)
public interface NotificationInternalClient {

    @GetMapping("/internal/notifications/mail/users/{accountId}/has-history")
    Map<String, Object> hasUserHistory(@PathVariable("accountId") Long accountId);
}
