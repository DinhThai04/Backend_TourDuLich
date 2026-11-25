package com.example.auth_service.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

public class FileInternalFeignConfiguration {

	@Value("${FILE_INTERNAL_KEY}")
	private String fileInternalKey;

	@Bean
	public RequestInterceptor fileInternalRequestInterceptor() {
		if (fileInternalKey == null || fileInternalKey.isBlank()) {
			throw new IllegalStateException("FILE_INTERNAL_KEY is required but not configured");
		}
		return new RequestInterceptor() {
			@Override
			public void apply(RequestTemplate template) {
				template.header("X-Internal-File-Key", fileInternalKey);
			}
		};
	}
}