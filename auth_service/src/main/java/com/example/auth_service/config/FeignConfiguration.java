package com.example.auth_service.config;

import feign.codec.Encoder;
import feign.form.spring.SpringFormEncoder;
import org.springframework.context.annotation.Bean;

// Not a scanned @Configuration: only FileServiceClient needs the multipart encoder,
// and a global FEIGN encoder bean would replace the default one for every client.
public class FeignConfiguration {
    @Bean
    public Encoder multipartFormEncoder() {
        return new SpringFormEncoder();
    }
}