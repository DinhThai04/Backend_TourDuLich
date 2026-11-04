package com.example.payment_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;
import java.time.ZoneId;

@Configuration(proxyBeanMethods = false)
public class PaymentClockConfig {
    @Bean public Clock paymentClock() { return Clock.system(ZoneId.of("Asia/Ho_Chi_Minh")); }
}
