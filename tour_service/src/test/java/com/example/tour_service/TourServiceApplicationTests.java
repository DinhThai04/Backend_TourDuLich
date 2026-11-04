package com.example.tour_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;
import com.example.tour_service.advice.AdviceTestDatabase;

@SpringBootTest
@ActiveProfiles("advice-test")
@Import(AdviceTestDatabase.class)
class TourServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
