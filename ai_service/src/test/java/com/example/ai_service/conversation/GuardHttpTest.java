package com.example.ai_service.conversation;

import com.example.ai_service.AiServiceApplication;
import com.example.ai_service.config.AiProperties;
import com.example.ai_service.guard.*;
import com.example.ai_service.model.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import jakarta.validation.Validator;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

/** Production guard/deadline/HTTP stack with a loopback model stub; no provider key/DB. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GuardHttpTest {
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    static int port;static final AtomicInteger calls=new AtomicInteger(),status=new AtomicInteger(200),delay=new AtomicInteger();
    static final AtomicBoolean huge=new AtomicBoolean();
    static class Time extends Clock { Instant value=Instant.parse("2026-10-23T08:00:00Z");public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return value;}void add(long seconds){value=value.plusSeconds(seconds);} }
    @TestConfiguration(proxyBeanMethods=false)
    static class Wiring {
        @Bean @Primary Time testClock(){return new Time();}
        @Bean @Primary LanguageModel guardedModel(ObjectMapper mapper,Validator validator,UsageBudget budget,GuardProperties limits) {
            var p=new AiProperties();p.setApiKey("dummy-key-never-print");p.setBaseUrl("http://127.0.0.1:"+port+"/openai");
            return new BudgetedLanguageModel(new SpringAiLanguageModel(GroqChatModelFactory.create(p),p.getModel(),mapper,validator),budget,limits,1024,p.getModel());
        }
    }
    HttpServer upstream;ExecutorService threads;ConfigurableApplicationContext context;String base;Time time;
    final HttpClient client=HttpClient.newHttpClient();
    @BeforeAll void start() throws Exception {
        upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);port=upstream.getAddress().getPort();threads=Executors.newVirtualThreadPerTaskExecutor();upstream.setExecutor(threads);
        upstream.createContext("/openai/v1/chat/completions",exchange->{
            calls.incrementAndGet();exchange.getRequestBody().readAllBytes();
            if(delay.get()>0)try{Thread.sleep(delay.get());}catch(InterruptedException ignored){}
            String output="{\"intent\":\"OUT_OF_SCOPE\",\"reset\":false,\"changes\":[],\"positions\":[],\"clarification\":\"NONE\"}";
            String body=status.get()==200?JSON.writeValueAsString(Map.of("id","stub","object","chat.completion","created",1,"model","openai/gpt-oss-120b","choices",List.of(Map.of("index",0,"finish_reason","stop","message",Map.of("role","assistant","content",output))),"usage",Map.of("prompt_tokens",100,"completion_tokens",20,"total_tokens",120))):"{\"error\":\"private-provider-sentinel\"}";
            if(huge.get())body="x".repeat(131073);
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");
            if(status.get()==429)exchange.getResponseHeaders().set("Retry-After","9");
            try{exchange.sendResponseHeaders(status.get(),bytes.length);exchange.getResponseBody().write(bytes);}catch(java.io.IOException disconnected){}finally{exchange.close();}
        });upstream.start();
        context=new SpringApplicationBuilder(AiServiceApplication.class,Wiring.class).run("--server.port=0","--app.ai.enabled=false","--app.ai.guard.requests-per-minute=1","--app.ai.guard.requests-per-day=2","--app.ai.guard.turn-timeout=1s");
        base="http://127.0.0.1:"+((ServletWebServerApplicationContext)context).getWebServer().getPort()+"/ai";time=context.getBean(Time.class);
    }
    @AfterAll void stop(){if(context!=null)context.close();if(upstream!=null)upstream.stop(0);if(threads!=null)threads.shutdownNow();}
    @BeforeEach void reset(){time.add(86401);calls.set(0);status.set(200);delay.set(0);huge.set(false);}
    HttpResponse<String> post(String path,Object body)throws Exception{return client.send(HttpRequest.newBuilder(URI.create(base+path)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());}
    String create()throws Exception{return JSON.readTree(post("/conversations",Map.of()).body()).path("result").path("conversationId").asText();}
    Map<String,String> chat(String id){return Map.of("conversationId",id,"requestId",UUID.randomUUID().toString(),"message","private-user-sentinel");}
    @Test void budget429AndReplayDoNotCallModelAgain()throws Exception{
        String id=create();var request=chat(id);assertThat(post("/chat",request).statusCode()).isEqualTo(200);
        assertThat(post("/chat",request).statusCode()).isEqualTo(200);
        var limited=post("/chat",chat(id));assertThat(limited.statusCode()).isEqualTo(429);assertThat(limited.headers().firstValue("Retry-After")).contains("60");assertThat(calls).hasValue(1);
    }
    @Test void rollingDailyQuotaIs503AndNotRetryable()throws Exception{
        String id=create();assertThat(post("/chat",chat(id)).statusCode()).isEqualTo(200);time.add(60);assertThat(post("/chat",chat(id)).statusCode()).isEqualTo(200);time.add(60);
        var response=post("/chat",chat(id));assertThat(response.statusCode()).isEqualTo(503);var error=JSON.readTree(response.body()).path("error");assertThat(error.path("type").asText()).isEqualTo("AI_QUOTA_EXHAUSTED");assertThat(error.path("retryable").asBoolean()).isFalse();assertThat(calls).hasValue(2);
        // The quota window is real, so the header must state how long it lasts instead of being missing.
        assertThat(response.headers().firstValue("Retry-After")).hasValueSatisfying(value->assertThat(Long.parseLong(value)).isPositive());assertThat(error.path("retryAfterSeconds").asLong()).isPositive();
    }
    @Test void providerRetryAfterCreatesLocalCooldownWithoutAnotherUpstreamRequest()throws Exception{
        status.set(429);String id=create();var first=post("/chat",chat(id));assertThat(first.statusCode()).isEqualTo(429);assertThat(first.headers().firstValue("Retry-After")).contains("9");
        var again=post("/chat",chat(id));assertThat(again.statusCode()).isEqualTo(429);assertThat(calls).hasValue(1);assertThat(again.body()).doesNotContain("private-provider-sentinel","dummy-key");
    }
    @Test void oversizedUpstreamBodyIsBoundedAndDoesNotCommit()throws Exception{
        huge.set(true);String id=create();assertThat(post("/chat",chat(id)).statusCode()).isEqualTo(502);
        assertThat(context.getBean(ConversationStore.class).snapshot(id).version()).isZero();
    }
    @Test @Order(99) void wholeTurnDeadlineReturns504AndLateWorkCannotCommit()throws Exception{
        delay.set(1800);String id=create();long start=System.nanoTime();var response=post("/chat",chat(id));
        assertThat(response.statusCode()).isEqualTo(504);assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(3));
        assertThat(context.getBean(ConversationStore.class).snapshot(id).version()).isZero();
    }
    @Test void telemetryIncludesTechnicalFieldsWithoutPromptSessionOrSecret()throws Exception{
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(ConversationService.class);
        var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();appender.start();logger.addAppender(appender);
        try{
            String id=create();var request=chat(id);assertThat(post("/chat",request).statusCode()).isEqualTo(200);
            String logs=appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).reduce("",String::concat);
            assertThat(logs).contains(request.get("requestId"),"status=200","model_calls=1","total_tokens=120").doesNotContain(id,"private-user-sentinel","private-provider-sentinel","dummy-key-never-print");
        }finally{logger.detachAppender(appender);appender.stop();}
    }
}
