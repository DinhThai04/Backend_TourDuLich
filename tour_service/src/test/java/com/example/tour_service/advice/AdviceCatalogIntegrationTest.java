package com.example.tour_service.advice;

import com.example.tour_service.dto.response.TourPriceResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real HTTP Feign calls to loopback stubs + real SQL against guarded H2, never DBtour. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("advice-test")
@Import(AdviceTestDatabase.class)
class AdviceCatalogIntegrationTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final HttpServer UPSTREAM=startUpstream();
    static final Map<Long,TourPriceResponse> PRICES=new ConcurrentHashMap<>();
    static final AtomicInteger PRICE_CALLS=new AtomicInteger(), MEDIA_CALLS=new AtomicInteger();
    static volatile int priceStatus=200,mediaStatus=200;
    static volatile String mediaQuery;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper mapper;
    @MockBean(name="adviceClock") Clock fixedClock;
    @DynamicPropertySource static void upstream(DynamicPropertyRegistry properties) {
        properties.add("app.advice.pricing-url",()->"http://127.0.0.1:"+UPSTREAM.getAddress().getPort()+"/pricing");
        properties.add("app.advice.media-url",()->"http://127.0.0.1:"+UPSTREAM.getAddress().getPort()+"/file");
    }
    static HttpServer startUpstream() {
        try {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/pricing/prices/batch",AdviceCatalogIntegrationTest::priceResponse);
            server.createContext("/file/media/tours",AdviceCatalogIntegrationTest::mediaResponse);
            server.start();return server;
        } catch(IOException failure){throw new ExceptionInInitializerError(failure);}
    }
    static void priceResponse(HttpExchange exchange) throws IOException {
        PRICE_CALLS.incrementAndGet();
        var request=JSON.readTree(exchange.getRequestBody());
        List<TourPriceResponse> selected=new ArrayList<>();
        for(var id:request.path("ids")) {var price=PRICES.get(id.asLong()); if(price!=null) selected.add(price);}
        respond(exchange,priceStatus,Map.of("code",1000,"result",selected));
    }
    static void mediaResponse(HttpExchange exchange) throws IOException {
        MEDIA_CALLS.incrementAndGet();mediaQuery=exchange.getRequestURI().getRawQuery();
        respond(exchange,mediaStatus,List.of(
                Map.of("tourId","2","url","javascript:alert(1)"),
                Map.of("tourId","2","url","https://res.cloudinary.com/demo/image/upload/f02.jpg"),
                Map.of("tourId","1","url","https://res.cloudinary.com/demo/image/upload/f01.jpg"),
                Map.of("tourId","999","url","https://res.cloudinary.com/demo/image/upload/unrequested.jpg")));
    }
    static void respond(HttpExchange exchange,int status,Object body) throws IOException {
        byte[] bytes=JSON.writeValueAsBytes(body);exchange.getResponseHeaders().add("Content-Type","application/json");
        exchange.sendResponseHeaders(status,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}exchange.close();
    }
    @AfterAll static void stopUpstream(){UPSTREAM.stop(0);}

    @BeforeEach void fixture() throws Exception {
        when(fixedClock.instant()).thenReturn(Instant.parse("2026-10-23T01:00:00Z"));
        when(fixedClock.getZone()).thenReturn(ZoneId.of("Asia/Ho_Chi_Minh"));
        try(var connection=dataSource.getConnection()) {
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:h2:mem:advice_test"),"Refuse to write outside isolated test database");
        }
        jdbc.update("DELETE FROM tour_departure");jdbc.update("DELETE FROM tour");jdbc.update("DELETE FROM location");
        PRICES.clear();PRICE_CALLS.set(0);MEDIA_CALLS.set(0);priceStatus=200;mediaStatus=200;mediaQuery=null;
        place(10,"Đà Nẵng","DEPARTURE");place(11,"Hà Nội","DEPARTURE");
        place(20,"Quảng Bình","DESTINATION");place(21,"Quảng Nam","DESTINATION");
        place(22,"Ninh Bình","DESTINATION");place(23,"An Giang","DESTINATION");
        place(24,"Sapa","DESTINATION");place(25,"Sa Pa","DESTINATION");
        tour(1,20,3600000,1800000,150000);tour(2,21,1100000,800,150);
        tour(3,22,2050000,1537500,0);tour(4,23,2890000,2050000,300);
        tour(5,20,2800000,1400000,0);tour(6,20,4990000,3500000,400);
        tour(7,21,2200000,1100000,0);tour(8,24,3290000,2300000,300);tour(9,25,3290000,2300000,300);
        departure(101,1,"2026-11-05T07:00",6);departure(102,2,"2026-11-06T07:30",20);
        departure(103,3,"2026-11-07T07:00",4);departure(104,4,"2026-11-08T06:30",0);
        departure(105,5,"2026-10-20T07:00",20);departure(106,6,"2026-11-09T07:00",10);
        departure(108,8,"2026-11-11T06:30",10);departure(109,9,"2026-11-12T06:30",10);
    }
    void place(int id,String name,String type){jdbc.update("INSERT INTO location VALUES(?,?,?)",id,name,type);}
    void tour(int id,int destination,long adult,long child,long infant){
        jdbc.update("INSERT INTO tour VALUES(?,?,?,?,?,?,?)",id,"F"+id,"Fixture "+id,3,id,10,destination);
        var p=new TourPriceResponse();p.setId((long)id);p.setAdultPrice(BigDecimal.valueOf(adult));p.setChildPrice(BigDecimal.valueOf(child));
        p.setToddlerPrice(BigDecimal.ZERO);p.setInfantPrice(BigDecimal.valueOf(infant));p.setSingleSupplementPrice(BigDecimal.valueOf(700000));PRICES.put((long)id,p);
    }
    void departure(int id,int tourId,String at,int seats){var time=LocalDateTime.parse(at);jdbc.update("INSERT INTO tour_departure(id,tour_id,departure_date,return_date,available_seats) VALUES(?,?,?,?,?)",id,tourId,time,time.plusDays(2),seats);}
    org.springframework.test.web.servlet.ResultActions search(String criteria) throws Exception {return search(criteria,0,5);}
    org.springframework.test.web.servlet.ResultActions search(String criteria,int page,int size) throws Exception {
        return mvc.perform(post("/advice/search").contentType("application/json").content("{\"criteria\":"+criteria+",\"page\":"+page+",\"size\":"+size+"}"));
    }
    @Test void regionBudgetAndBatchingBeforePagination(){
        assertDoesNotThrow(()->search("{\"region\":\"CENTRAL\",\"maxBudgetVnd\":5000000,\"budgetScope\":\"PER_ADULT\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.tours[*].tourId").value(org.hamcrest.Matchers.contains(2,1,6)))
                .andExpect(jsonPath("$.result.searchSummary.matchingCatalogCount").value(5))
                .andExpect(jsonPath("$.result.searchSummary.matchingBookableCount").value(3))
                .andExpect(jsonPath("$.result.tours[0].price.amountVnd").value(1100000))
                .andExpect(jsonPath("$.result.tours[0].price.isEstimate").value(true))
                .andExpect(jsonPath("$.result.tours[0].imageUrl").value("https://res.cloudinary.com/demo/image/upload/f02.jpg")));
        assertEquals(1,PRICE_CALLS.get());assertEquals(1,MEDIA_CALLS.get());assertFalse(mediaQuery.contains("tourIds=5"));
    }
    @Test void changingBudgetReadsUpdatedDatabaseInsteadOfOldResults() throws Exception {
        search("{\"region\":\"CENTRAL\",\"maxBudgetVnd\":5000000,\"budgetScope\":\"PER_ADULT\"}").andExpect(status().isOk());
        departure(107,7,"2026-11-10T07:00",10);
        search("{\"region\":\"CENTRAL\",\"maxBudgetVnd\":3000000,\"budgetScope\":\"PER_ADULT\"}")
                .andExpect(jsonPath("$.result.tours[*].tourId").value(org.hamcrest.Matchers.contains(2,7)));
        assertEquals(2,PRICE_CALLS.get());
    }
    @Test void groupQuotePreserves800VndAndDoesNotAddRoomSupplement() throws Exception {
        search("{\"destinationId\":21,\"budgetScope\":\"TOTAL_GROUP\",\"party\":{\"adults\":2,\"children\":1,\"toddlers\":0,\"infants\":0,\"unclassifiedChildren\":0}}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.tours[0].price.amountVnd").value(2200800))
                .andExpect(jsonPath("$.result.tours[0].price.singleSupplementIncluded").value(false));
    }
    @Test void groupFiltersBeforePageAndAppliesBudgetToGroupNotAdult() throws Exception {
        search("{\"region\":\"CENTRAL\",\"budgetScope\":\"TOTAL_GROUP\",\"maxBudgetVnd\":3000000,\"party\":{\"adults\":2,\"children\":1,\"toddlers\":0,\"infants\":0,\"unclassifiedChildren\":0}}",0,1)
                .andExpect(jsonPath("$.result.tours[*].tourId").value(org.hamcrest.Matchers.contains(2)))
                .andExpect(jsonPath("$.result.searchSummary.hasMore").value(false));
    }
    @Test void catalogSeparatesNoFutureNoMatchingAndInsufficientSeats() throws Exception {
        search("{\"region\":\"CENTRAL\",\"destinationId\":20,\"searchMode\":\"CATALOG\",\"departureFrom\":\"2026-11-09\",\"departureTo\":\"2026-11-09\"}")
                .andExpect(jsonPath("$.result.tours[0].availability.status").value("NO_FUTURE_DEPARTURE"))
                .andExpect(jsonPath("$.result.tours[1].availability.status").value("NO_MATCHING_DEPARTURE"))
                .andExpect(jsonPath("$.result.tours[2].availability.status").value("AVAILABLE"));
        search("{\"region\":\"SOUTH\",\"searchMode\":\"CATALOG\"}")
                .andExpect(jsonPath("$.result.tours[0].availability.status").value("INSUFFICIENT_SEATS"));
    }
    @Test void allPassengerTypesUseSeatsEvenIfTheirPriceIsZero() throws Exception {
        search("{\"destinationId\":22,\"party\":{\"adults\":2,\"children\":0,\"toddlers\":2,\"infants\":1,\"unclassifiedChildren\":0}}")
                .andExpect(jsonPath("$.result.tours").isEmpty()).andExpect(jsonPath("$.result.searchSummary.matchingCatalogCount").value(1));
    }
    @Test void aliasGroupIsResolvedFromLiveIdsNotSnapshotIds() throws Exception {
        search("{\"destinationId\":24}").andExpect(jsonPath("$.result.tours[*].tourId").value(org.hamcrest.Matchers.contains(8,9)));
        mvc.perform(get("/advice/locations")).andExpect(status().isOk())
                .andExpect(jsonPath("$.result[?(@.id == 25)].canonicalId").value(org.hamcrest.Matchers.contains(24)));
    }
    @Test void filtersBeforeTopKAndReportsHasMore() throws Exception {
        for(int id=30;id<38;id++){tour(id,21,1000000+id,500000,0);departure(id+200,id,"2026-11-01T06:00",20);}
        search("{\"region\":\"CENTRAL\"}",0,2).andExpect(jsonPath("$.result.tours[*].tourId").value(org.hamcrest.Matchers.contains(30,31)))
                .andExpect(jsonPath("$.result.searchSummary.hasMore").value(true));
        search("{\"region\":\"CENTRAL\"}",1,2).andExpect(jsonPath("$.result.tours[*].tourId").value(org.hamcrest.Matchers.contains(32,33)));
    }
    @Test void datesAreInclusiveAndCurrentDayPastHourIsNotBookable() throws Exception {
        departure(201,2,"2026-11-01T00:00",20);departure(202,2,"2026-11-30T23:59",20);
        departure(203,2,"2026-12-01T00:00",20);departure(204,2,"2026-10-23T07:59",20);
        search("{\"destinationId\":21,\"departureFrom\":\"2026-11-30\",\"departureTo\":\"2026-11-30\"}")
                .andExpect(jsonPath("$.result.tours[0].availability.departureId").value(202));
        search("{\"destinationId\":21,\"departureFrom\":\"2026-11-01\",\"departureTo\":\"2026-11-01\"}")
                .andExpect(jsonPath("$.result.tours[0].availability.departureId").value(201));
        search("{\"departureTo\":\"2026-10-23\"}").andExpect(jsonPath("$.result.tours").isEmpty());
    }
    @Test void emptyCatalogDoesNotCallPricesOrMedia() throws Exception {
        jdbc.update("DELETE FROM tour_departure");jdbc.update("DELETE FROM tour");
        search("{}").andExpect(status().isOk()).andExpect(jsonPath("$.result.searchSummary.matchingCatalogCount").value(0));
        assertEquals(0,PRICE_CALLS.get());assertEquals(0,MEDIA_CALLS.get());
    }
    @Test void priceHttpFailureIs503NotEmptySearchOrLogin401() throws Exception {
        priceStatus=401;
        search("{}").andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.type").value("CATALOG_UNAVAILABLE"));
        assertEquals(1,PRICE_CALLS.get());assertEquals(0,MEDIA_CALLS.get());
    }
    @Test void missingAndNegativePriceFailClosed() throws Exception {
        PRICES.remove(2L);search("{\"region\":\"CENTRAL\"}").andExpect(status().isServiceUnavailable());
        PRICES.get(1L).setAdultPrice(BigDecimal.valueOf(-1));search("{\"destinationId\":20}").andExpect(status().isServiceUnavailable());
    }
    @Test void optionalImageFailureKeepsValidResults() throws Exception {
        mediaStatus=500;search("{\"destinationId\":21}").andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tours[0].imageUrl").isEmpty());assertEquals(1,MEDIA_CALLS.get());
    }
    @Test void malformedFutureDepartureIs503() throws Exception {
        jdbc.update("UPDATE tour_departure SET return_date=departure_date-INTERVAL '1' DAY WHERE id=102");
        search("{\"destinationId\":21}").andExpect(status().isServiceUnavailable());
    }
    @Test void regionUsesDestinationRatherThanDanangOrigin() throws Exception {
        search("{\"region\":\"NORTH\"}").andExpect(jsonPath("$.result.tours[*].tourId").value(org.hamcrest.Matchers.contains(3,8,9)));
    }
    @ParameterizedTest @ValueSource(strings={
            "{\"region\":\"CENTRAL\",\"destinationId\":24}","{\"destinationId\":10}","{\"originId\":20}","{\"destinationId\":999}",
            "{\"maxBudgetVnd\":3000000}","{\"maxBudgetVnd\":-1,\"budgetScope\":\"PER_ADULT\"}",
            "{\"maxBudgetVnd\":3.1,\"budgetScope\":\"PER_ADULT\"}","{\"maxBudgetVnd\":1000000000001,\"budgetScope\":\"PER_ADULT\"}",
            "{\"maxBudgetVnd\":\"3000000\",\"budgetScope\":\"PER_ADULT\"}","{\"destinationId\":20.5}","{\"region\":\"UNKNOWN\"}",
            "{\"departureFrom\":\"2026-12-01\",\"departureTo\":\"2026-11-01\"}","{\"departureFrom\":\"2026-02-30\"}",
            "{\"budgetScope\":\"TOTAL_GROUP\"}","{\"party\":{\"adults\":-1}}","{\"party\":{\"adults\":101}}",
            "{\"party\":{\"adults\":0,\"children\":0,\"toddlers\":0,\"infants\":0,\"unclassifiedChildren\":0}}",
            "{\"budgetScope\":\"TOTAL_GROUP\",\"party\":{\"adults\":2,\"children\":0,\"toddlers\":0,\"infants\":0,\"unclassifiedChildren\":1}}",
            "{\"userId\":1}","{\"party\":{\"adults\":2,\"secret\":true}}",
            "{\"budgetScope\":0}","{\"party\":{\"adults\":\"2\"}}",
            "{\"budgetScope\":\"TOTAL_GROUP\",\"party\":{\"adults\":2,\"children\":null,\"toddlers\":0,\"infants\":0,\"unclassifiedChildren\":0}}"})
    void invalidCriteriaDoesNotCallUpstream(String criteria) throws Exception {
        search(criteria).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.type").value("INVALID_REQUEST"));
        assertEquals(0,PRICE_CALLS.get());assertEquals(0,MEDIA_CALLS.get());
    }
    @Test void invalidPageBodyAndWriteRoutesStayClosed() throws Exception {
        search("{}",-1,5).andExpect(status().isBadRequest());search("{}",0,6).andExpect(status().isBadRequest());
        mvc.perform(post("/advice/search").contentType("application/json").content("{\"criteria\":{},\"role\":\"admin\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/advice/search").contentType("application/json").content("{" )).andExpect(status().isBadRequest());
        mvc.perform(post("/tours")).andExpect(status().isUnauthorized());
        mvc.perform(get("/advice/search")).andExpect(status().isUnauthorized());
        mvc.perform(post("/advice/locations")).andExpect(status().isUnauthorized());
        assertEquals(0,PRICE_CALLS.get());
    }
    @Test void duplicateFieldsAndOversizedBodyAreRejectedBeforeUpstream() throws Exception {
        mvc.perform(post("/advice/search").contentType("application/json")
                .content("{\"criteria\":{\"region\":\"NORTH\",\"region\":\"CENTRAL\"}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/advice/search").contentType("application/json").content(" ".repeat(17000)+"{}"))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.error.type").value("PAYLOAD_TOO_LARGE"));
        assertEquals(0,PRICE_CALLS.get());assertEquals(0,MEDIA_CALLS.get());
    }
    @Test void boundedRepositoryFailsInsteadOfReturningTruncatedCounts() {
        var bounded=new AdviceCatalogRepository(new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(dataSource),
                new AdviceConfiguration.Limits(1,1,100));
        assertEquals(503,assertThrows(AdviceException.class,bounded::candidates).status());
        assertEquals(503,assertThrows(AdviceException.class,()->bounded.futureDepartures(List.of(1,2),LocalDateTime.of(2026,10,23,8,0))).status());
    }
    @Test void freeInfantStillUsesSeatAndAmountsStayIntegerVnd() throws Exception {
        search("{\"destinationId\":22,\"budgetScope\":\"TOTAL_GROUP\",\"maxBudgetVnd\":0,\"party\":{\"adults\":0,\"children\":0,\"toddlers\":0,\"infants\":1,\"unclassifiedChildren\":0}}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.tours[0].price.amountVnd").value(0));
    }
    @Test void vietnameseNamesAreUtf8AndImageUrlsAreRestricted() throws Exception {
        var result=search("{\"destinationId\":21}").andExpect(status().isOk()).andReturn();
        assertEquals("Quảng Nam",mapper.readTree(result.getResponse().getContentAsByteArray()).at("/result/tours/0/destinationName").asText());
        assertFalse(AdviceService.safeImage("https://evil.example/photo.jpg"));
        assertFalse(AdviceService.safeImage("https://user@res.cloudinary.com/demo/image/upload/a.jpg"));
        assertFalse(AdviceService.safeImage("http://res.cloudinary.com/demo/image/upload/a.jpg"));
    }
}
