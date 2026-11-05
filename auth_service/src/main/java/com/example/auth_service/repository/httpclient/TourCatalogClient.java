package com.example.auth_service.repository.httpclient;

import com.example.auth_service.config.CatalogKeyRequestInterceptor;
import java.util.List;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

@FeignClient(name = "tour-catalog", url = "${app.services.tour-base-url:http://localhost:8086/tour}",
        configuration = CatalogKeyRequestInterceptor.class)
public interface TourCatalogClient {
    record Entry(int id, String tourTitle) {}
    record Snapshot(List<Entry> tours) {}

    @GetMapping("/tours/search-snapshot")
    Snapshot snapshot();
}
