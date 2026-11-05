package com.example.ai_service.probe;

import com.example.ai_service.config.AiProperties;
import com.example.ai_service.model.LanguageModel;
import com.example.ai_service.model.ModelFailure;
import com.example.ai_service.model.ModelTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Opt-in CLI only: one fixed Vietnamese/JSON probe, no database, email or catalog access. */
@Component
@ConditionalOnProperty(name = "app.ai.probe", havingValue = "true")
public class ProbeRunner implements ApplicationRunner {
    private final LanguageModel model;
    private final ObjectMapper json;
    private final AiProperties properties;
    private int exitCode = 1;
    public ProbeRunner(LanguageModel model, ObjectMapper json, AiProperties properties) {
        this.model = model;
        this.json = json;
        this.properties = properties;
    }
    public record ProbeOutput(@NotBlank @Pattern(regexp = "SGU Tour") String brand,
                              @NotBlank @Pattern(regexp = "vi") String language,
                              @NotBlank @Size(max = 300) @Pattern(regexp = "[^<>\\p{Cntrl}]+") String greeting) {}
    public record ProbeReport(boolean success, String provider, String model, Object result,
                              String errorType, Integer retryAfterSeconds) {}

    public static ModelTask<ProbeOutput> task() {
        return new ModelTask<>("Bạn là trợ lý của SGU Tour. Chỉ trả JSON theo schema được cung cấp. "
                + "brand phải là SGU Tour, language phải là vi. Không thêm suy luận, HTML, thông tin giá hoặc tour.",
                "Viết một lời chào ngắn, lịch sự bằng tiếng Việt mời khách chia sẻ nhu cầu du lịch.", ProbeOutput.class);
    }
    @Override public void run(ApplicationArguments args) throws Exception {
        ProbeReport report;
        try {
            var result = model.generate(task());
            report = new ProbeReport(true, properties.getProvider(), result.model(), result, null, null);
            exitCode = 0;
        } catch (ModelFailure failure) {
            report = new ProbeReport(false, properties.getProvider(), properties.getModel(), null,
                    failure.type().name(), failure.retryAfterSeconds());
        }
        // CLI contract: the probe result is parsed from stdout, so this stays a plain line.
        System.out.println("SGU_AI_PROBE=" + json.writeValueAsString(report));
    }
    public int exitCode() { return exitCode; }
}
