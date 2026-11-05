package com.example.ai_service.conversation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.time.*;

@Configuration(proxyBeanMethods=false)
@EnableScheduling
@EnableConfigurationProperties(SessionProperties.class)
public class ConversationConfiguration {
    @Bean Clock aiClock() { return Clock.system(ZoneId.of("Asia/Ho_Chi_Minh")); }
}
