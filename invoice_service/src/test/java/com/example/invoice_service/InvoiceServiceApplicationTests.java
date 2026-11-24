package com.example.invoice_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
		"BOOKING_INTERNAL_KEY=test-booking-key",
		"AUTH_INTERNAL_KEY=test-auth-key",
		"spring.rabbitmq.listener.simple.auto-startup=false"
})
class InvoiceServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
