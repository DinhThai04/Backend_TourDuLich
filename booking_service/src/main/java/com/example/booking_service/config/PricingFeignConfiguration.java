package com.example.booking_service.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PricingFeignConfiguration {

	@Value("${PRICING_INTERNAL_KEY}")
	private String pricingInternalKey;

	@Bean
	public RequestInterceptor pricingInternalRequestInterceptor() {
		if (pricingInternalKey == null || pricingInternalKey.isBlank()) {
			throw new IllegalStateException("PRICING_INTERNAL_KEY is required but not configured");
		}
		return new RequestInterceptor() {
			@Override
			public void apply(RequestTemplate template) {
				template.header("X-Internal-Pricing-Key", pricingInternalKey);
			}
		};
	}
}