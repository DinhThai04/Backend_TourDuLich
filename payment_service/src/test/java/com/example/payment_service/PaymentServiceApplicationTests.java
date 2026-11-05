package com.example.payment_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:payment-context;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.show-sql=false",
        "vnpay.secret-key=synthetic-test-hash-secret",
        "vnpay.tmn-code=synthetic-test-tmn-code",
        "jwt.signerKey=synthetic-test-signer-key-012345678901234567890123456789012345678901234567890123"
})
class PaymentServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
