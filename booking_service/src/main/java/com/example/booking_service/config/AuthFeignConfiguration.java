package com.example.booking_service.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

public class AuthFeignConfiguration {

	@Value("${AUTH_INTERNAL_KEY}")
	private String authInternalKey;

	@Bean
	public RequestInterceptor authInternalAuthInterceptor() {
		if (authInternalKey == null || authInternalKey.isBlank()) {
			throw new IllegalStateException("AUTH_INTERNAL_KEY is required but not configured");
		}
		return new RequestInterceptor() {
			@Override
			public void apply(RequestTemplate template) {
				template.header("X-Internal-Auth-Key", authInternalKey);
			}
		};
	}
}