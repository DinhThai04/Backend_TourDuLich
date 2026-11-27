package com.example.auth_service.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

public class PaymentFeignConfiguration {

	@Value("${PAYMENT_INTERNAL_KEY}")
	private String paymentInternalKey;

	@Bean
	public RequestInterceptor paymentInternalRequestInterceptor() {
		if (paymentInternalKey == null || paymentInternalKey.isBlank()) {
			throw new IllegalStateException("PAYMENT_INTERNAL_KEY is required but not configured");
		}
		return new RequestInterceptor() {
			@Override
			public void apply(RequestTemplate template) {
				template.header("X-Internal-Payment-Key", paymentInternalKey);
			}
		};
	}
}