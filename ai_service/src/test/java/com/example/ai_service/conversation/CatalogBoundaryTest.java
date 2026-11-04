package com.example.ai_service.conversation;

import com.example.ai_service.catalog.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static com.example.ai_service.conversation.ChatModels.*;

class CatalogBoundaryTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final AtomicReference<String> body=new AtomicReference<>();
    final AtomicReference<String> path=new AtomicReference<>();
    final AtomicInteger status=new AtomicInteger(200),calls=new AtomicInteger();
    HttpServer server; CatalogReader reader;
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            calls.incrementAndGet(); path.set(exchange.getRequestURI().getPath()); exchange.getRequestBody().readAllBytes();
            byte[] bytes=body.get().getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status.get(),bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        reader=new CatalogConfiguration().catalogReader("http://127.0.0.1:"+server.getAddress().getPort()+"/api/v1/tour",json);
        body.set(json.writeValueAsString(Envelope.ok("fixture",new ConversationServiceTest.FakeCatalog().search(Criteria.empty(),0))));
    }
    @AfterEach void stop() { server.stop(0); }
    @ParameterizedTest @ValueSource(ints={400,401,403,429,500,503})
    void upstreamErrorsAreNotEmptyResultsOrWebsiteUnauthorizedAndNotRetried(int value) {
        status.set(value); assertThatThrownBy(()->reader.search(Criteria.empty(),0)).hasMessage("CATALOG_UNAVAILABLE");
        assertThat(calls.get()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"not json","{\"code\":1000,\"result\":null}","{\"code\":999,\"result\":{}}"})
    void malformedDataIsNotNoResults(String value) {
        body.set(value); assertThatThrownBy(()->reader.search(Criteria.empty(),0)).hasMessage("CATALOG_UNAVAILABLE");
    }
    @Test void unsafeImageIsOptionalButForgedDetailPathIsRejected() throws Exception {
        var root=(ObjectNode)json.readTree(body.get()); var first=(ObjectNode)root.path("result").path("tours").get(0);
        first.put("imageUrl","https://attacker.example/pixel"); body.set(root.toString());
        assertThat(reader.search(Criteria.empty(),0).tours().getFirst().imageUrl()).isNull();
        first.put("detailPath","https://attacker.example/pay"); body.set(root.toString());
        assertThatThrownBy(()->reader.search(Criteria.empty(),0)).hasMessage("CATALOG_UNAVAILABLE");
    }
    @Test void invalidCurrencyOrPageCannotBeReturnedAsValidQuote() throws Exception {
        var root=(ObjectNode)json.readTree(body.get());
        ((ObjectNode)root.path("result").path("tours").get(0).path("price")).put("currency","USD"); body.set(root.toString());
        assertThatThrownBy(()->reader.search(Criteria.empty(),0)).hasMessage("CATALOG_UNAVAILABLE");
        body.set(json.writeValueAsString(Envelope.ok("fixture",new ConversationServiceTest.FakeCatalog().search(Criteria.empty(),1))));
        assertThatThrownBy(()->reader.search(Criteria.empty(),0)).hasMessage("CATALOG_UNAVAILABLE");
    }
    @Test void locationsAliasMustResolveToExistingSameTypeCanonicalRecord() throws Exception {
        body.set(json.writeValueAsString(Envelope.ok("fixture",CriteriaMergerTest.PLACES)));
        assertThat(reader.locations()).hasSize(4); assertThat(path.get()).isEqualTo("/api/v1/tour/advice/locations");
        body.set(json.writeValueAsString(Envelope.ok("fixture",List.of(new Location(1,"Sa Pa","DESTINATION",Region.NORTH,99,List.of(1,99),true)))));
        assertThatThrownBy(reader::locations).hasMessage("CATALOG_UNAVAILABLE");
    }
    @Test void configurationRejectsRemoteHostCredentialsAndArbitraryPaths() {
        for(var url:List.of("https://example.com/api/v1/tour","http://secret@localhost:8888/api/v1/tour","http://localhost:8888/api/v1/payment"))
            assertThatThrownBy(()->new CatalogConfiguration().catalogReader(url,json)).hasMessage("Invalid local catalog URL.");
    }
    @Test void configurationAcceptsComposeServiceHosts() {
        assertThatCode(()->new CatalogConfiguration().catalogReader("http://api_gateway:8888/api/v1/tour",json)).doesNotThrowAnyException();
        assertThatCode(()->new com.example.ai_service.account.HttpBookingReader("http://booking_service:8081/booking",json)).doesNotThrowAnyException();
    }
}
