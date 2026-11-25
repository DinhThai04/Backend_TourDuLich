package com.example.booking_service.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

public class InvoiceFeignConfiguration {

	@Value("${INVOICE_INTERNAL_KEY}")
	private String invoiceInternalKey;

	@Bean
	public RequestInterceptor invoiceInternalAuthInterceptor() {
		if (invoiceInternalKey == null || invoiceInternalKey.isBlank()) {
			throw new IllegalStateException("INVOICE_INTERNAL_KEY is required but not configured");
		}
		return new RequestInterceptor() {
			@Override
			public void apply(RequestTemplate template) {
				template.header("X-Internal-Invoice-Key", invoiceInternalKey);
			}
		};
	}
}