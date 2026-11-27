package com.example.booking_service.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

public class TourFeignConfiguration {

	@Value("${TOUR_INTERNAL_KEY}")
	private String tourInternalKey;

	@Bean
	public RequestInterceptor tourInternalAuthInterceptor() {
		if (tourInternalKey == null || tourInternalKey.isBlank()) {
			throw new IllegalStateException("TOUR_INTERNAL_KEY is required but not configured");
		}
		return new RequestInterceptor() {
			@Override
			public void apply(RequestTemplate template) {
				template.header("X-Internal-Tour-Key", tourInternalKey);
			}
		};
	}
}