package com.example.auth_service.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BookingFeignConfiguration {

	@Value("${BOOKING_INTERNAL_KEY}")
	private String bookingInternalKey;

	@Bean
	public RequestInterceptor bookingInternalRequestInterceptor() {
		if (bookingInternalKey == null || bookingInternalKey.isBlank()) {
			throw new IllegalStateException("BOOKING_INTERNAL_KEY is required but not configured");
		}
		return new RequestInterceptor() {
			@Override
			public void apply(RequestTemplate template) {
				template.header("X-Internal-Booking-Key", bookingInternalKey);
			}
		};
	}
}