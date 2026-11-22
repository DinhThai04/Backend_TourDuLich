package com.example.auth_service.repository.httpclient;

import com.example.auth_service.config.TourFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@FeignClient(name = "tour-service-internal", url = "${app.services.tour-base-url:http://localhost:8086/tour}", configuration = TourFeignConfiguration.class)
public interface TourInternalClient {

    @GetMapping("/internal/tour/departures/{id}")
    Map<String, Object> getDeparture(@PathVariable("id") Integer id);
}