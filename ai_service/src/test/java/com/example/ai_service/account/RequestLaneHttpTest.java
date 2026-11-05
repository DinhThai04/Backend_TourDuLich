package com.example.ai_service.account;

import com.example.ai_service.AiServiceApplication;
import com.example.ai_service.conversation.PlanInterpreter;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
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
import static com.example.ai_service.conversation.ChatModels.*;
import static org.assertj.core.api.Assertions.*;

/** Real Boot HTTP with two request lanes: saturating one must never block or queue the other. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RequestLaneHttpTest {
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    static final String BEARER="Bearer fixture.jwt.owner-seven.123456789";
    static final String HELP_BODY="{\"message\":\"Booking #6 còn bao lâu?\"}";
    static final AtomicReference<CountDownLatch> bookingGate=new AtomicReference<>(),chatGate=new AtomicReference<>();
    static final AtomicReference<CountDownLatch> bookingEntered=new AtomicReference<>(),chatEntered=new AtomicReference<>();
    @TestConfiguration(proxyBeanMethods=false)
    static class Wiring {
        /** Blocks only while a test holds the gate; the default path stays deterministic and model-free. */
        @Bean @Primary PlanInterpreter lanePlanInterpreter() {
            return (state,message)->{
                var entered=chatEntered.get();if(entered!=null)entered.countDown();
                await(chatGate.get());
                return new Plan(Intent.OUT_OF_SCOPE,false,List.of(),List.of(),Hint.NONE);
            };
        }
    }
    HttpServer upstream;ExecutorService threads;ConfigurableApplicationContext app;String base;
    final HttpClient http=HttpClient.newHttpClient();
    @BeforeAll void start() throws Exception {
        upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        threads=Executors.newVirtualThreadPerTaskExecutor();
        upstream.setExecutor(threads);
        upstream.createContext("/booking/advice/mine",x->{
            var entered=bookingEntered.get();if(entered!=null)entered.countDown();
            await(bookingGate.get());
            var now=OffsetDateTime.parse("2026-11-04T08:00:00+07:00");
            var item=new BookingReader.Booking(6,3,"TOUR_03","Đà Nẵng — Cổng Trời Đông Giang",now.minusDays(3),
                    now.plusDays(16),"HELD","HELD",now.plusDays(3),259200,now,"reservation-v1");
            byte[] body=JSON.writeValueAsBytes(Map.of("code",1000,"result",new BookingReader.Page(List.of(item),false,now)));
            x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(200,body.length);
            x.getResponseBody().write(body);x.close();
        });
        upstream.start();
        app=new SpringApplicationBuilder(AiServiceApplication.class,Wiring.class).run("--server.port=0","--app.ai.enabled=false",
                "--app.ai.booking-base-url=http://127.0.0.1:"+upstream.getAddress().getPort()+"/booking");
        base="http://127.0.0.1:"+((ServletWebServerApplicationContext)app).getWebServer().getPort()+"/ai";
    }
    @AfterAll void stop() {if(app!=null)app.close();if(upstream!=null)upstream.stop(0);if(threads!=null)threads.shutdownNow();}
    @BeforeEach void reset() {
        bookingGate.set(null);chatGate.set(null);bookingEntered.set(null);chatEntered.set(null);
    }
    HttpResponse<String> post(String path,String body,String auth) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base+path)).header("Content-Type","application/json");
        if(auth!=null)request.header("Authorization",auth);
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
    String conversation(String owner) throws Exception {
        var response=http.send(HttpRequest.newBuilder(URI.create(base+"/conversations"))
                .header("Content-Type","application/json").header("X-Ai-Owner",owner)
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());
        return JSON.readTree(response.body()).path("result").path("conversationId").asText();
    }
    String chatBody(String conversationId) throws Exception {
        return JSON.writeValueAsString(Map.of("conversationId",conversationId,"requestId",UUID.randomUUID().toString(),
                "message","Tìm tour miền Trung"));
    }
    @Test void saturatedBookingHelpLaneRejectsWithRetryAfterWhileChatStillAnswers() throws Exception {
        var release=new CountDownLatch(1);var arrived=new CountDownLatch(2);
        bookingGate.set(release);bookingEntered.set(arrived);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=pool.submit(()->post("/booking-help",HELP_BODY,BEARER));
            var second=pool.submit(()->post("/booking-help",HELP_BODY,BEARER));
            assertThat(arrived.await(5,TimeUnit.SECONDS)).isTrue();
            var rejected=post("/booking-help",HELP_BODY,BEARER);
            assertThat(rejected.statusCode()).isEqualTo(503);
            assertThat(JSON.readTree(rejected.body()).path("error").path("type").asText()).isEqualTo("AI_CAPACITY_REACHED");
            assertThat(rejected.headers().firstValue("Retry-After"))
                    .hasValueSatisfying(value->assertThat(Long.parseLong(value)).isPositive());
            var chat=post("/chat",chatBody(conversation("owner-help-lane")),null);
            assertThat(chat.statusCode()).isEqualTo(200);
            release.countDown();
            assertThat(first.get(5,TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(second.get(5,TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        } finally {release.countDown();}
    }
    @Test void saturatedChatLaneDoesNotBlockBookingHelp() throws Exception {
        var release=new CountDownLatch(1);var entered=new CountDownLatch(1);
        chatGate.set(release);chatEntered.set(entered);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=pool.submit(()->post("/chat",chatBody(conversation("owner-chat-lane")),null));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            var rejected=post("/chat",chatBody(conversation("owner-chat-lane")),null);
            assertThat(rejected.statusCode()).isEqualTo(503);
            assertThat(JSON.readTree(rejected.body()).path("error").path("type").asText()).isEqualTo("AI_CAPACITY_REACHED");
            assertThat(rejected.headers().firstValue("Retry-After"))
                    .hasValueSatisfying(value->assertThat(Long.parseLong(value)).isPositive());
            var help=post("/booking-help",HELP_BODY,BEARER);
            assertThat(help.statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(help.body()).path("result").path("reply").asText()).contains("3 ngày");
            release.countDown();
            assertThat(first.get(5,TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        } finally {release.countDown();}
    }
    private static void await(CountDownLatch gate) {
        if(gate==null) return;
        try { gate.await(10,TimeUnit.SECONDS); }
        catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
