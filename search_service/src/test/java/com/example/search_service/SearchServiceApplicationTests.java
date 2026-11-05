package com.example.search_service;

import com.example.search_service.repository.TourSearchRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.redis.core.RedisTemplate;

@SpringBootTest(properties = {
		"spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.elasticsearch.ElasticsearchRepositoriesAutoConfiguration",
		"spring.data.elasticsearch.repositories.enabled=false",
		"spring.data.redis.repositories.enabled=false",
		"app.search.sync-enabled=false",
		"jwt.signerKey=test-signer-key-0123456789abcdefghijklmnopqrstuvwxyz-ABCDEFGHIJKLMNOPQRSTUVWXYZ-0123456789"
})
class SearchServiceApplicationTests {

	@MockBean TourSearchRepository tourSearchRepository;
	@MockBean ElasticsearchOperations elasticsearchOperations;
	@MockBean RedisTemplate<String, Object> redisTemplate;

	@Test
	void contextLoads() {
	}

}
