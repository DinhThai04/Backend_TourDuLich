package com.example.ai_service.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class LocalKeyEnvironmentTest {
    @TempDir Path temp;
    @Test void literalKeyOnlyAndQuotesSupportedWithoutExecutingOtherValues() throws Exception {
        var file=temp.resolve(".env");
        Files.writeString(file,"AI_ENABLED=false\nAI_MODEL=unapproved\nGROQ_API_KEY='dummy-key-123456789'\nOTHER=$(never-execute)\n");
        assertThat(LocalKeyEnvironment.readKey(file)).isEqualTo("dummy-key-123456789");
    }
    @Test void badWhitespaceAndDuplicateKeysFailWithoutDisclosingValues() throws Exception {
        var file=temp.resolve(".env");Files.writeString(file,"GROQ_API_KEY=secret key value\n");
        assertThatThrownBy(()->LocalKeyEnvironment.readKey(file)).hasMessage("Invalid local AI provider key.");
        Files.writeString(file,"GROQ_API_KEY=dummy-one\nGROQ_API_KEY=dummy-two\n");
        assertThatThrownBy(()->LocalKeyEnvironment.readKey(file)).hasMessage("Duplicate provider key in local AI configuration.");
    }
    @Test void environmentKeyHasPriorityAndOfflineSwitchIsRespected() {
        var env=new MockEnvironment().withProperty("GROQ_API_KEY","dummy-process-key").withProperty("AI_ENABLED","false");
        new LocalKeyEnvironment().postProcessEnvironment(env,null);
        assertThat(env.getProperty("GROQ_API_KEY")).isEqualTo("dummy-process-key");
        assertThat(env.resolvePlaceholders("${AI_ENABLED:${app.ai.local-key-present:false}}")).isEqualTo("false");
        var enabled=new MockEnvironment().withProperty("GROQ_API_KEY","dummy-process-key");
        new LocalKeyEnvironment().postProcessEnvironment(enabled,null);
        assertThat(enabled.resolvePlaceholders("${AI_ENABLED:${app.ai.local-key-present:false}}")).isEqualTo("true");
    }
    @Test void explicitOfflineDoesNotReadEvenAnInvalidLocalKeyFile() throws Exception {
        Files.writeString(temp.resolve(".env"),"GROQ_API_KEY=invalid key value");
        String previous=System.getProperty("user.dir");
        try {
            System.setProperty("user.dir",temp.toString());
            for(String name:java.util.List.of("AI_ENABLED","app.ai.enabled")) {
                var env=new MockEnvironment().withProperty(name,"false");
                assertThatCode(()->new LocalKeyEnvironment().postProcessEnvironment(env,null)).doesNotThrowAnyException();
                assertThat(env.getProperty("GROQ_API_KEY")).isNull();
            }
        } finally {System.setProperty("user.dir",previous);}
    }
}
