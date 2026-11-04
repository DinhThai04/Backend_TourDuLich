package com.example.ai_service;

import com.example.ai_service.config.AiProperties;
import com.example.ai_service.config.BookingHelpProperties;
import com.example.ai_service.guard.GuardProperties;
import com.example.ai_service.probe.ProbeRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.env.SimpleCommandLinePropertySource;

@SpringBootApplication
@EnableConfigurationProperties({AiProperties.class, GuardProperties.class, BookingHelpProperties.class})
public class AiServiceApplication {
    public static void main(String[] args) {
        // Probe exits after one fixed request, without exposing an HTTP model endpoint.
        boolean probeArgument=Boolean.parseBoolean(new SimpleCommandLinePropertySource(args).getProperty("app.ai.probe"));
        var application=new SpringApplication(AiServiceApplication.class);
        if(probeArgument) application.setWebApplicationType(WebApplicationType.NONE);
        var context=application.run(args);
        boolean probe=probeArgument || context.getEnvironment().getProperty("app.ai.probe",Boolean.class,false);
        if(probe) {
            int result=context.getBean(ProbeRunner.class).exitCode();
            context.close();
            System.exit(result);
        }
    }
}
