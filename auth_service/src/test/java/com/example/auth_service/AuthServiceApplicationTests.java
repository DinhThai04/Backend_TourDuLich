package com.example.auth_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
		"FILE_INTERNAL_KEY=test-file-key",
		"PAYMENT_INTERNAL_KEY=test-payment-key",
		"TOUR_INTERNAL_KEY=test-tour-key",
		"BOOKING_INTERNAL_KEY=test-booking-key",
		"INVOICE_INTERNAL_KEY=test-invoice-key",
		"NOTIFICATION_INTERNAL_KEY=test-notification-key",
		"CATALOG_INTERNAL_KEY=test-catalog-key"
})
class AuthServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
