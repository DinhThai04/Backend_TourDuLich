package com.example.tour_service.advice;

import feign.Request;
import feign.Retryer;
import org.springframework.context.annotation.Bean;
import java.util.concurrent.TimeUnit;

/** Feign child-context configuration; intentionally not a global @Configuration. */
public class AdviceFeignConfiguration {
    @Bean Request.Options adviceHttpOptions() {
        return new Request.Options(2, TimeUnit.SECONDS, 4, TimeUnit.SECONDS, false);
    }
    @Bean Retryer adviceRetryer() { return Retryer.NEVER_RETRY; }
}
