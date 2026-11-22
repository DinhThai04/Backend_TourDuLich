package com.example.booking_service.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NotificationFeignConfiguration {

	@Value("${NOTIFICATION_INTERNAL_KEY}")
	private String notificationInternalKey;

	@Bean
	public RequestInterceptor notificationInternalAuthInterceptor() {
		if (notificationInternalKey == null || notificationInternalKey.isBlank()) {
			throw new IllegalStateException("NOTIFICATION_INTERNAL_KEY is required but not configured");
		}
		return new RequestInterceptor() {
			@Override
			public void apply(RequestTemplate template) {
				template.header("X-Internal-Notification-Key", notificationInternalKey);
			}
		};
	}
}