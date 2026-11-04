package com.example.ai_service;

import com.example.ai_service.model.*;
import com.example.ai_service.probe.ProbeRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import java.net.URI;
import java.net.http.*;
import static org.assertj.core.api.Assertions.*;

class OfflineApplicationTest {
    @Test void realHttpHealthWorksWithoutProviderAndInvalidChatIsRejected() throws Exception {
        try (var context = new SpringApplicationBuilder(AiServiceApplication.class).run("--server.port=0",
                "--server.address=127.0.0.1", "--app.ai.enabled=false", "--app.ai.probe=false", "--app.ai.api-key=", "--app.ai.faq-approved-version=")) {
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            var url = "http://127.0.0.1:" + port + "/ai";
            try (var client = HttpClient.newHttpClient()) {
                var health = client.send(HttpRequest.newBuilder(URI.create(url + "/health")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(health.statusCode()).isEqualTo(200);
                var data = new ObjectMapper().readTree(health.body());
                assertThat(data.path("result").path("configured").asBoolean()).isFalse();
                assertThat(data.path("result").path("providerConnection").asText()).isEqualTo("NOT_CHECKED");
                assertThat(health.body()).doesNotContain("apiKey", "api-key", "GROQ_API_KEY");
                var chat = client.send(HttpRequest.newBuilder(URI.create(url + "/chat"))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(), HttpResponse.BodyHandlers.ofString());
                assertThat(chat.statusCode()).isEqualTo(400);
            }
            var failure = catchThrowableOfType(() -> context.getBean(LanguageModel.class).generate(ProbeRunner.task()), ModelFailure.class);
            assertThat(failure.type()).isEqualTo(ModelFailure.Type.AI_PROVIDER_NOT_CONFIGURED);
            assertThat(failure.retryable()).isFalse();
            assertThat(context.containsBean("probeRunner")).isFalse();
        }
    }
}
