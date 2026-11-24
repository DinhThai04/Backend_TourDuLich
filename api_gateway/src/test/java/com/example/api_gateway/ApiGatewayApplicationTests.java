package com.example.api_gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"MAIL_INTERNAL_API_KEY=test-mail-key"})
class ApiGatewayApplicationTests {

	@Test
	void contextLoads() {
	}

}
