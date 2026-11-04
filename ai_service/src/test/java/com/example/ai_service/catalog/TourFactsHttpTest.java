package com.example.ai_service.catalog;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class TourFactsHttpTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final Clock clock=Clock.fixed(Instant.parse("2026-10-23T01:00:00Z"),ZoneId.of("Asia/Ho_Chi_Minh"));
    final AtomicInteger status=new AtomicInteger(200),calls=new AtomicInteger();
    final AtomicReference<String> path=new AtomicReference<>(),tour=new AtomicReference<>(),itinerary=new AtomicReference<>();
    HttpServer server; TourFactsReader reader;
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            calls.incrementAndGet(); path.set(exchange.getRequestURI().getPath());
            assertThat(exchange.getRequestMethod()).isEqualTo("GET"); assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
            String content=path.get().contains("/itineraries/")?itinerary.get():tour.get();
            byte[] bytes=content.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status.get(),bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        reader=new CatalogConfiguration().tourFactsReader("http://127.0.0.1:"+server.getAddress().getPort()+"/api/v1/tour",json,clock);
        tour.set("""
            {"code":1000,"result":{"id":901,"tourTitle":"<b>Tour Quảng Nam</b>","duration":3,
              "basePrice":999,"destinationCity":{"city":"Quảng Nam"},"tourPrice":{"adultPrice":2600000,"childPrice":800},
              "departures":[{"id":8,"tourId":901,"departureDate":"2026-11-01T07:00:00","returnDate":"2026-11-03T17:00:00","availableSeats":0}]}}
            """);
        itinerary.set("""
            {"code":1000,"result":[
              {"id":12,"dayNumber":2,"title":"<b>Ngày 2</b>","description":"<p>Biển &amp; núi</p><script>alert('bad')</script>","meal":null},
              {"id":11,"dayNumber":1,"title":"Ngày 1","description":"Khởi hành","meal":"<p>Trưa</p>"}]}
            """);
    }
    @AfterEach void stop() { server.stop(0); }
    @Test void readsAdultFareInsteadOfBasePriceAndDoesNotEquateFutureWithSeats() {
        var facts=reader.facts(901);
        assertThat(facts.adultPriceVnd()).isEqualTo(2600000); assertThat(facts.hasFutureDeparture()).isTrue();
        assertThat(facts.title()).isEqualTo("Tour Quảng Nam"); assertThat(path.get()).isEqualTo("/api/v1/tour/tours/901");
    }
    @Test void sortedItineraryIsPlainTextAndDoesNotExecuteOrFetchHtml() {
        var detail=reader.detail(reader.facts(901));
        assertThat(detail.itinerary()).extracting(com.example.ai_service.conversation.ChatModels.ItineraryDay::dayNumber).containsExactly(1,2);
        assertThat(detail.itinerary().get(1).description()).isEqualTo("Biển & núi");
        assertThat(detail.itinerary().get(1).meal()).isEmpty(); assertThat(detail.truncated()).isFalse();
        assertThat(path.get()).isEqualTo("/api/v1/tour/itineraries/tour/901"); assertThat(calls.get()).isEqualTo(2);
    }
    @ParameterizedTest @ValueSource(ints={404,400})
    void onlyConfirmedMissingTourReturnsNull(int code) {
        status.set(code); tour.set("{\"code\":1001}"); assertThat(reader.facts(901)).isNull(); assertThat(calls.get()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(ints={400,401,403,429,500,503})
    void otherErrorsAreNotMissingToursOrWebsiteUnauthorized(int code) {
        status.set(code); tour.set("{\"code\":9999,\"message\":\"private upstream error\"}");
        assertThatThrownBy(()->reader.facts(901)).hasMessage("CATALOG_UNAVAILABLE"); assertThat(calls.get()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"wrongId","negativePrice","fractionalPrice","missingPrice","stringPrice","badDeparture","missingDepartures"})
    void malformedFactsCannotProduceInventedValues(String kind) throws Exception {
        var root=(ObjectNode)json.readTree(tour.get()); var data=(ObjectNode)root.path("result");
        switch(kind) {
            case "wrongId" -> data.put("id",902);
            case "negativePrice" -> ((ObjectNode)data.path("tourPrice")).put("adultPrice",-1);
            case "fractionalPrice" -> ((ObjectNode)data.path("tourPrice")).put("adultPrice",1.5);
            case "missingPrice" -> ((ObjectNode)data.path("tourPrice")).remove("adultPrice");
            case "stringPrice" -> ((ObjectNode)data.path("tourPrice")).put("adultPrice","2600000");
            case "badDeparture" -> ((ObjectNode)data.path("departures").get(0)).put("returnDate","2020-01-01T00:00:00");
            case "missingDepartures" -> data.remove("departures");
        }
        tour.set(root.toString()); assertThatThrownBy(()->reader.facts(901)).hasMessage("CATALOG_UNAVAILABLE");
    }
    @Test void repeatedReadGetsChangedPriceAndEmptyItineraryStaysEmpty() throws Exception {
        assertThat(reader.facts(901).adultPriceVnd()).isEqualTo(2600000);
        var root=(ObjectNode)json.readTree(tour.get()); ((ObjectNode)root.path("result").path("tourPrice")).put("adultPrice",3000000); tour.set(root.toString());
        var facts=reader.facts(901); assertThat(facts.adultPriceVnd()).isEqualTo(3000000);
        itinerary.set("{\"code\":1000,\"result\":[]}"); assertThat(reader.detail(facts).itinerary()).isEmpty();
    }
    @Test void duplicateOrInvalidItineraryDaysAreDataErrors() throws Exception {
        var facts=reader.facts(901); var root=(ObjectNode)json.readTree(itinerary.get());
        ((ObjectNode)root.path("result").get(0)).put("dayNumber",1); itinerary.set(root.toString());
        assertThatThrownBy(()->reader.detail(facts)).hasMessage("CATALOG_UNAVAILABLE");
        ((ObjectNode)root.path("result").get(0)).put("dayNumber",4); itinerary.set(root.toString());
        assertThatThrownBy(()->reader.detail(facts)).hasMessage("CATALOG_UNAVAILABLE");
    }
    @Test void longDetailRetainsAllDaysAndExplicitlyMarksTruncation() throws Exception {
        var root=(ObjectNode)json.readTree(tour.get()); ((ObjectNode)root.path("result")).put("duration",30); tour.set(root.toString());
        var rows=json.createArrayNode();
        for(int day=1;day<=30;day++) rows.addObject().put("dayNumber",day).put("title","Ngày "+day).put("description","😀".repeat(1000)).put("meal","Trưa");
        itinerary.set(json.writeValueAsString(Map.of("code",1000,"result",rows)));
        var detail=reader.detail(reader.facts(901)); assertThat(detail.itinerary()).hasSize(30); assertThat(detail.truncated()).isTrue();
        assertThat(detail.itinerary().stream().mapToInt(d->count(d.title())+count(d.description())+count(d.meal())).sum()).isLessThanOrEqualTo(6000);
    }
    @Test void oversizedHttpBodyAndTooManyDaysAreRejected() throws Exception {
        var facts=reader.facts(901); tour.set("x".repeat(1_048_577));
        assertThatThrownBy(()->reader.facts(901)).hasMessage("CATALOG_UNAVAILABLE");
        var rows=json.createArrayNode(); for(int day=1;day<=31;day++) rows.addObject().put("dayNumber",day).put("title","Day");
        itinerary.set(json.writeValueAsString(Map.of("code",1000,"result",rows)));
        assertThatThrownBy(()->reader.detail(facts)).hasMessage("CATALOG_UNAVAILABLE");
    }
    private int count(String text) { return text.codePointCount(0,text.length()); }
}
