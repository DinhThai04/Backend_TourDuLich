package com.example.api_gateway;

import com.example.api_gateway.config.AuthenticationFilter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class ApiGatewayApplication {

	public static void main(String[] args) {
		SpringApplication.run(ApiGatewayApplication.class, args);
	}

	@Bean
	public GlobalFilter authenticationFilter(IdentityService identityService,
	                                         ObjectMapper objectMapper,
	                                         AuthCircuitBreaker authCircuitBreaker,
	                                         IntrospectionCache introspectionCache) {
		return new AuthenticationFilter(identityService, objectMapper, authCircuitBreaker, introspectionCache);
	}
}
