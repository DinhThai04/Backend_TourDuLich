package com.example.api_gateway;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import com.example.api_gateway.service.IdentityService;
import com.example.api_gateway.dto.response.ApiResponse;
import com.example.api_gateway.dto.response.IntrospectResponse;
import reactor.core.publisher.Mono;
import static org.mockito.Mockito.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class AiGatewayTest {
    static final AtomicReference<String> received = new AtomicReference<>();
    static final AtomicReference<String> authorization = new AtomicReference<>();
    static final AtomicReference<String> cookie = new AtomicReference<>();
    static final HttpServer upstream;
    static {
        try {
            upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            upstream.createContext("/ai", exchange -> {
                received.set(exchange.getRequestMethod()+" "+exchange.getRequestURI().getPath());
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
                exchange.getRequestBody().readAllBytes();
                boolean chat=exchange.getRequestURI().getPath().equals("/ai/chat");
                byte[] bytes=(chat?"{\"code\":5030,\"error\":{\"type\":\"AI_PROVIDER_UNAVAILABLE\"},\"result\":null}":"{\"code\":1000,\"result\":{\"turnVersion\":0}}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(chat?503:200,bytes.length);
                exchange.getResponseBody().write(bytes); exchange.close();
            });
            upstream.start();
        } catch(Exception error) { throw new ExceptionInInitializerError(error); }
    }
    @DynamicPropertySource static void config(DynamicPropertyRegistry properties) {
        properties.add("app.ai.base-url",()->"http://127.0.0.1:"+upstream.getAddress().getPort());
    }
    @Autowired WebTestClient client;
    @MockBean IdentityService identity;
    @AfterAll static void stop() { upstream.stop(0); }

    @Test void exactPublicPostsStripPrefixAndPrivateHeaders() {
        for(String path:new String[]{"conversations","conversations/reset"}) {
            client.post().uri("/api/v1/ai/"+path).header("Authorization","malformed-public-token")
                .header("Cookie","session=private").header("Content-Type","application/json").bodyValue("{}")
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.code").isEqualTo(1000);
            assertEquals("POST /ai/"+path,received.get());
            assertNull(authorization.get()); assertNull(cookie.get());
        }
    }
    @Test void providerFailureStays503RatherThanLogin401() {
        client.post().uri("/api/v1/ai/chat").header("Content-Type","application/json").bodyValue("{}")
            .exchange().expectStatus().isEqualTo(503).expectBody()
            .jsonPath("$.error.type").isEqualTo("AI_PROVIDER_UNAVAILABLE");
        assertEquals("POST /ai/chat",received.get());
    }
    @Test void healthAdminUnknownAndWrongMethodsAreNotRouted() {
        received.set(null);
        for(String path:new String[]{"health","admin","conversations/extra","chat/extra"})
            client.post().uri("/api/v1/ai/"+path).bodyValue("{}").exchange().expectStatus().isNotFound();
        client.get().uri("/api/v1/ai/chat").exchange().expectStatus().isNotFound();
        client.delete().uri("/api/v1/ai/conversations").exchange().expectStatus().isNotFound();
        assertNull(received.get());
    }
    @Test void browserPreflightAndExistingProtectedRouteStillWork() {
        client.options().uri("/api/v1/ai/chat").header("Origin","http://localhost:5173")
            .header("Access-Control-Request-Method","POST").header("Access-Control-Request-Headers","content-type")
            .exchange().expectStatus().isOk().expectHeader()
            .valueEquals("Access-Control-Allow-Origin","http://localhost:5173");
        client.options().uri("/api/v1/ai/chat").header("Origin","https://evil.example")
            .header("Access-Control-Request-Method","POST").header("Access-Control-Request-Headers","content-type")
            .exchange().expectStatus().isForbidden();
        client.get().uri("/api/v1/booking/bookings").exchange().expectStatus().isUnauthorized();
    }
    @Test void publishedPoliciesStripCredentialsAndAnonymousHelpCanExplainLogin() {
        client.get().uri("/api/v1/ai/policies").header("Authorization","invalid-public-token").header("Cookie","private=yes")
                .exchange().expectStatus().isOk();
        assertEquals("GET /ai/policies",received.get());assertNull(authorization.get());assertNull(cookie.get());
        client.post().uri("/api/v1/ai/booking-help").bodyValue("{}").exchange().expectStatus().isOk();
        assertEquals("POST /ai/booking-help",received.get());assertNull(authorization.get());
    }
    @Test void personalHelpIntrospectsAndForwardsOnlyTheVerifiedBearer() {
        when(identity.introspect("fixture-valid-jwt")).thenReturn(Mono.just(ApiResponse.<IntrospectResponse>builder().result(IntrospectResponse.builder().valid(true).build()).build()));
        client.post().uri("/api/v1/ai/booking-help").header("Authorization","Bearer fixture-valid-jwt").header("Cookie","private=yes")
                .bodyValue("{}").exchange().expectStatus().isOk();
        assertEquals("Bearer fixture-valid-jwt",authorization.get());assertNull(cookie.get());
        verify(identity).introspect("fixture-valid-jwt");
        received.set(null);
        client.post().uri("/api/v1/ai/booking-help").header("Authorization","bad").bodyValue("{}").exchange().expectStatus().isUnauthorized();
        assertNull(received.get());
        when(identity.introspect("fixture-revoked")).thenReturn(Mono.just(ApiResponse.<IntrospectResponse>builder().result(IntrospectResponse.builder().valid(false).build()).build()));
        client.post().uri("/api/v1/ai/booking-help").header("Authorization","Bearer fixture-revoked").bodyValue("{}").exchange().expectStatus().isUnauthorized();
        assertNull(received.get());
    }
}
