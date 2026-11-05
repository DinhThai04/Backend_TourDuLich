package com.example.auth_service.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;

/** Attaches the shared internal catalogue key; the snapshot is not a public storefront read. */
public class CatalogKeyRequestInterceptor implements RequestInterceptor {
    private final String key;

    public CatalogKeyRequestInterceptor(@Value("${app.catalog.internal-key:}") String key) {
        this.key = key;
    }

    @Override
    public void apply(RequestTemplate template) {
        if (StringUtils.hasText(key)) template.header("X-Catalog-Key", key);
    }
}
