package com.example.ai_service.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Native Boot startup: read only the provider key, never execute a dotenv file. */
public final class LocalKeyEnvironment implements EnvironmentPostProcessor, Ordered {
    private static final String PROVIDER_KEY="GROQ_API_KEY";
    private static final long MAX_CONFIG_BYTES=16384;
    private static final int MAX_KEY_LENGTH=512;
    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
    @Override public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        var values = new LinkedHashMap<String,Object>();
        boolean offline="false".equalsIgnoreCase(environment.getProperty("AI_ENABLED"));
        for(var source:environment.getPropertySources()) {
            Object explicit=source.getProperty("app.ai.enabled");
            if(explicit!=null) { offline|="false".equalsIgnoreCase(explicit.toString());break; }
        }
        if(offline) {
            values.put("app.ai.local-key-present",false);
            environment.getPropertySources().addLast(new MapPropertySource("sguLocalProviderKey",values));
            return;
        }
        String key = environment.getProperty(PROVIDER_KEY, "");
        if (key.isBlank()) {
            var cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
            for (var candidate : List.of(cwd.resolve(".env"), cwd.resolve("ai_service/.env"),
                    cwd.resolve("Backend_TourDuLich/ai_service/.env"))) {
                if (!Files.isRegularFile(candidate)) continue;
                key = readKey(candidate);
                if (!key.isBlank()) { values.put(PROVIDER_KEY, key); break; }
            }
        }
        values.put("app.ai.local-key-present", !key.isBlank());
        environment.getPropertySources().addLast(new MapPropertySource("sguLocalProviderKey", values));
    }
    static String readKey(Path file) {
        try {
            if (Files.size(file) > MAX_CONFIG_BYTES) throw new IllegalStateException("Local AI configuration is too large.");
            String found = "";
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String value = line.strip().replaceFirst("^\\uFEFF", "");
                if (!value.matches(PROVIDER_KEY+"\\s*=.*")) continue;
                if (!found.isEmpty()) throw new IllegalStateException("Duplicate provider key in local AI configuration.");
                value = value.substring(value.indexOf('=') + 1).strip();
                if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) value = value.substring(1,value.length()-1);
                if (value.length() > MAX_KEY_LENGTH || value.chars().anyMatch(Character::isWhitespace))
                    throw new IllegalStateException("Invalid local AI provider key.");
                found = value;
            }
            return found;
        } catch (IOException failure) { throw new IllegalStateException("Cannot read local AI provider configuration."); }
    }
}
