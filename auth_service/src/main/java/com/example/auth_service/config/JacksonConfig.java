package com.example.auth_service.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {

    private final ObjectMapper objectMapper;

    public JacksonConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void registerModules() {
        objectMapper.registerModule(new JavaTimeModule());
        // Nếu bạn không muốn timestamp dạng số
        objectMapper.findAndRegisterModules();
    }
}
