package com.example.ai_service;

import com.example.ai_service.config.AiProperties;
import com.example.ai_service.model.ModelTask;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

class ConfigurationTest {
    @Test void disabledNeedsNoKeyAndDoesNotPrintKey() {
        var properties = new AiProperties();
        properties.validate();
        properties.setApiKey("fake-secret-never-print");
        assertThat(properties.toString()).doesNotContain("fake-secret");
    }
    @Test void enablingWithoutKeyFailsBeforeClientCreation() {
        var properties = new AiProperties(); properties.setEnabled(true);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GROQ_API_KEY");
        properties.setApiKey("bad\r\nheader");
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
    }
    @Test void providerAndModelCannotSilentlyChangeToPaidOrOtherHost() {
        var properties = new AiProperties(); properties.setModel("unknown-paid-model");
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
        properties = new AiProperties(); properties.setBaseUrl("https://wrong-host.test");
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
    }
    @Test void timeoutAndTokenConfigurationBounded() {
        var properties = new AiProperties(); properties.setMaxCompletionTokens(9000);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
        properties = new AiProperties(); properties.setReadTimeout(Duration.ofMinutes(5));
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
        properties = new AiProperties(); properties.setConnectTimeout(Duration.ZERO);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
    }
    @Test void tasksHaveInputBoundsAndRedactedToString() {
        assertThatThrownBy(() -> new ModelTask<>("system", " ", String.class)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelTask<>("system", "x".repeat(32001), String.class)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ModelTask<>("secret-system", "private-user", String.class).toString())
                .doesNotContain("secret-system", "private-user");
    }
}
