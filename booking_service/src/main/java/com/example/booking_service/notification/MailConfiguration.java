package com.example.booking_service.notification;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.time.Clock;
import java.time.ZoneId;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(MailProperties.class)
public class MailConfiguration {
    @Bean
    public Clock mailClock() {
        return Clock.system(ZoneId.of("Asia/Ho_Chi_Minh"));
    }
}
