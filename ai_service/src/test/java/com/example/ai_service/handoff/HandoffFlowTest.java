package com.example.ai_service.handoff;

import com.example.ai_service.AiServiceApplication;
import com.example.ai_service.catalog.*;
import com.example.ai_service.config.AiProperties;
import com.example.ai_service.conversation.ChatModels.*;
import com.example.ai_service.guard.*;
import com.example.ai_service.model.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import jakarta.validation.Validator;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

/** Opt-in: real gateway JAR -> Boot AI/guard/Spring AI -> local model/catalog HTTP fixtures. */
@EnabledIfSystemProperty(named="sgu.ai.gateway-jar",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HandoffFlowTest {
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    static final AtomicReference<Plan> PLAN=new AtomicReference<>();
    static int upstreamPort;
    static class Time extends Clock {
        volatile Instant at=Instant.parse("2026-11-01T01:00:00Z");
        public ZoneId getZone(){return ZoneId.of("Asia/Ho_Chi_Minh");}
        public Clock withZone(ZoneId ignored){return this;}public Instant instant(){return at;}
        void nextMinute(){at=at.plusSeconds(61);}
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class Wiring {
        @Bean @Primary Time testTime(){return new Time();}
        @Bean @Primary LanguageModel modelFixture(ObjectMapper json,Validator validator,UsageBudget budget,GuardProperties limits){
            var p=new AiProperties();p.setApiKey("dummy-handoff-key");p.setBaseUrl("http://127.0.0.1:"+upstreamPort+"/openai");
            return new BudgetedLanguageModel(new SpringAiLanguageModel(GroqChatModelFactory.create(p),p.getModel(),json,validator),budget,limits,1024,p.getModel());
        }
        @Bean @Primary CatalogReader catalogFixture(ObjectMapper json){return new CatalogConfiguration().catalogReader("http://127.0.0.1:"+upstreamPort+"/api/v1/tour",json);}
        @Bean @Primary TourFactsReader factsFixture(ObjectMapper json,Clock clock){return new CatalogConfiguration().tourFactsReader("http://127.0.0.1:"+upstreamPort+"/api/v1/tour",json,clock);}
    }
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    final AtomicInteger modelCalls=new AtomicInteger(),modelStatus=new AtomicInteger(200),catalogStatus=new AtomicInteger(200);
    final AtomicBoolean privateHeaderSeen=new AtomicBoolean(),wrongProviderKey=new AtomicBoolean();
    final List<JsonNode> queries=new CopyOnWriteArrayList<>();
    final List<Long> fares=new CopyOnWriteArrayList<>(List.of(2000000L,4000000L,1500000L));
    final List<String> names=List.of("Đà Nẵng – Hội An fixture","Huế fixture","Sa Pa fixture");
    HttpServer upstream;ExecutorService threads;ConfigurableApplicationContext context;Process gateway;String base;Time time;
    boolean scenarioPassed;
    record Reply(int status,JsonNode json) {JsonNode result(){return json.path("result");}}
    @BeforeAll void start()throws Exception{
        Path gatewayJar=Path.of(System.getProperty("sgu.ai.gateway-jar")).toAbsolutePath();
        assertThat(gatewayJar).isRegularFile();
        upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);upstreamPort=upstream.getAddress().getPort();
        threads=Executors.newVirtualThreadPerTaskExecutor();upstream.setExecutor(threads);upstream.createContext("/",this::fixture);upstream.start();
        context=new SpringApplicationBuilder(AiServiceApplication.class,Wiring.class).run("--server.port=0","--app.ai.enabled=false","--app.ai.probe=false","--app.ai.faq-approved-version=");
        time=context.getBean(Time.class);int aiPort=((ServletWebServerApplicationContext)context).getWebServer().getPort();
        int gatewayPort;try(var socket=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){gatewayPort=socket.getLocalPort();}
        Path java=Path.of(System.getProperty("java.home"),"bin","java.exe");
        gateway=new ProcessBuilder(java.toString(),"-Xms64m","-Xmx192m","-jar",gatewayJar.toString(),"--server.address=127.0.0.1","--server.port="+gatewayPort,"--app.ai.base-url=http://127.0.0.1:"+aiPort)
                .redirectErrorStream(true).redirectOutput(Path.of("target","H_gateway_flow.log").toFile()).start();
        base="http://127.0.0.1:"+gatewayPort+"/api/v1/ai";
        boolean ready=false;for(int i=0;i<100;i++){
            if(!gateway.isAlive())throw new IllegalStateException("Owned gateway exited; see target/H_gateway_flow.log");
            try{if(post("/conversations",Map.of()).status()==201){ready=true;break;}}catch(java.io.IOException notReady){}
            Thread.sleep(200);
        }
        assertThat(ready).as("owned gateway readiness").isTrue();
    }
    @AfterAll void stop()throws Exception{
        if(gateway!=null && gateway.isAlive()){gateway.destroy();if(!gateway.waitFor(5,TimeUnit.SECONDS)){gateway.destroyForcibly();gateway.waitFor(5,TimeUnit.SECONDS);}}
        if(context!=null)context.close();if(upstream!=null)upstream.stop(0);if(threads!=null)threads.shutdownNow();client.close();
        var report=new LinkedHashMap<String,Object>();report.put("checkedAt",Instant.now().toString());report.put("scenarioPassed",scenarioPassed);
        report.put("gatewayPid",gateway==null?null:gateway.pid());report.put("gatewayStopped",gateway==null || !gateway.isAlive());
        report.put("modelFixtureCalls",modelCalls.get());report.put("catalogSearchFixtureCalls",queries.size());report.put("realModelCalls",0);report.put("databaseAccess",false);
        report.put("privateHeaderSeenByCatalog",privateHeaderSeen.get());report.put("providerUsedOnlyDummyKey",!wrongProviderKey.get());
        JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of("target","H_flow_summary.json").toFile(),report);
        assertThat(gateway==null || !gateway.isAlive()).as("owned gateway process stopped").isTrue();
    }
    void fixture(com.sun.net.httpserver.HttpExchange exchange)throws java.io.IOException{
        String path=exchange.getRequestURI().getPath();String raw=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);int status=200;Object body;
        try{
            if(path.equals("/openai/v1/chat/completions")){
                modelCalls.incrementAndGet();wrongProviderKey.set(wrongProviderKey.get() || !"Bearer dummy-handoff-key".equals(exchange.getRequestHeaders().getFirst("Authorization")));
                status=modelStatus.get();body=status==200?Map.of("id","fixture","object","chat.completion","created",1,"model","openai/gpt-oss-120b","choices",List.of(Map.of("index",0,"finish_reason","stop","message",Map.of("role","assistant","content",JSON.writeValueAsString(PLAN.get())))),"usage",Map.of("prompt_tokens",100,"completion_tokens",20,"total_tokens",120)):Map.of("error","private-provider-body");
                if(status==429)exchange.getResponseHeaders().set("Retry-After","2");
            }else{
                if(exchange.getRequestHeaders().containsKey("Authorization") || exchange.getRequestHeaders().containsKey("Cookie"))privateHeaderSeen.set(true);
                if(path.equals("/api/v1/tour/advice/search")){
                    status=catalogStatus.get();JsonNode query=JSON.readTree(raw);queries.add(query);body=status==200?search(query):Map.of("error","private-catalog-body");
                }else if(path.equals("/api/v1/tour/advice/locations")){
                    var locations=new ArrayList<Location>();for(int id=1;id<=3;id++)locations.add(new Location(id,id==1?"Đà Nẵng":id==2?"Huế":"Sa Pa","DESTINATION",region(id),id,List.of(id),true));body=Envelope.ok("fixture",locations);
                }else if(path.matches("/api/v1/tour/tours/[1-3]")){
                    int id=Integer.parseInt(path.substring(path.lastIndexOf('/')+1));
                    body=Envelope.ok("fixture",Map.of("id",id,"tourTitle",names.get(id-1),"duration",2,"basePrice",999,"destinationCity",Map.of("city",id==3?"Sa Pa":"Miền Trung"),"tourPrice",Map.of("adultPrice",fares.get(id-1)),"departures",List.of()));
                }else if(path.matches("/api/v1/tour/itineraries/tour/[1-3]"))body=Envelope.ok("fixture",List.of(Map.of("dayNumber",1,"title","Ngày một","description","<p>Tham quan fixture</p><script>ignore_rules()</script>","meal","Trưa")));
                else{status=404;body=Map.of();}
            }
            byte[] bytes=JSON.writeValueAsBytes(body);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
        }finally{exchange.close();}
    }
    Region region(int id){return id==3?Region.NORTH:Region.CENTRAL;}
    Object search(JsonNode query)throws java.io.IOException{
        Criteria c=JSON.treeToValue(query.path("criteria"),Criteria.class);var cards=new ArrayList<TourCard>();var stamp=OffsetDateTime.now(time);
        for(int id=1;id<=3;id++){
            if(c.region()!=null && c.region()!=region(id) || c.destinationId()!=null && c.destinationId()!=id || c.maxBudgetVnd()!=null && fares.get(id-1)>c.maxBudgetVnd())continue;
            cards.add(new TourCard(id,"FIXTURE_"+id,names.get(id-1),id==3?"Sa Pa":"Miền Trung",region(id),2,null,"/tours/detail/"+id,new Price(fares.get(id-1),"VND",BudgetScope.PER_ADULT,true,false,stamp),new Availability("AVAILABLE",id,OffsetDateTime.parse("2026-12-20T08:00:00+07:00"),20,stamp)));
        }
        return Envelope.ok("fixture",new SearchResult(cards,new Summary(cards.size(),false,cards.size(),cards.size(),stamp),query.path("page").asInt(),5));
    }
    Reply post(String path,Object body)throws Exception{
        var response=client.send(HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(45)).header("Content-Type","application/json; charset=utf-8").header("Authorization","malformed-website-jwt").header("Cookie","website=private").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body),StandardCharsets.UTF_8)).build(),HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(),JSON.readTree(response.body()));
    }
    Plan plan(Change... changes){return new Plan(Intent.SEARCH_TOURS,false,List.of(changes),List.of(),Hint.NONE);}
    Change set(Field field,String value){return new Change(field,Action.SET,value);}
    Reply ask(String token,Plan plan,String text)throws Exception{time.nextMinute();PLAN.set(plan);return post("/chat",Map.of("conversationId",token,"requestId",UUID.randomUUID().toString(),"message",text));}
    @Test void multiTurnGatewayModelAndCatalogContractsRemainGrounded()throws Exception{
        String token=post("/conversations",Map.of()).result().path("conversationId").asText();
        var initial=plan(set(Field.REGION,"CENTRAL"),set(Field.MAX_BUDGET_VND,"5000000"),set(Field.BUDGET_SCOPE,"PER_ADULT"));
        var found=ask(token,initial,"Miền Trung dưới 5 triệu cho một người lớn");assertThat(found.status()).isEqualTo(200);assertThat(found.result().path("tours").size()).isEqualTo(2);
        var cheaper=ask(token,plan(set(Field.MAX_BUDGET_VND,"3000000")),"Giờ chỉ còn 3 triệu");assertThat(cheaper.result().path("criteria").path("region").asText()).isEqualTo("CENTRAL");assertThat(cheaper.result().path("tours").size()).isEqualTo(1);assertThat(queries.getLast().path("criteria").path("maxBudgetVnd").asLong()).isEqualTo(3000000);
        var north=ask(token,plan(set(Field.REGION,"NORTH")),"Đổi sang miền Bắc");assertThat(north.result().path("criteria").path("maxBudgetVnd").asLong()).isEqualTo(3000000);assertThat(north.result().path("tours").get(0).path("tourId").asInt()).isEqualTo(3);
        var detail=ask(token,new Plan(Intent.DETAIL_TOUR,false,List.of(),List.of(1),Hint.NONE),"Lịch trình tour thứ nhất");assertThat(detail.result().path("detail").path("tourId").asInt()).isEqualTo(3);assertThat(detail.result().path("detail").toString()).doesNotContain("<script>","ignore_rules");
        var resetPlan=new Plan(Intent.SEARCH_TOURS,true,initial.changes(),List.of(),Hint.NONE);assertThat(ask(token,resetPlan,"Tìm lại từ đầu: miền Trung dưới 5 triệu cho một người lớn").result().path("tours").size()).isEqualTo(2);
        fares.set(0,2500000L);var compare=ask(token,new Plan(Intent.COMPARE_TOURS,false,List.of(),List.of(1,2),Hint.NONE),"So sánh tour thứ nhất và thứ hai");assertThat(compare.result().path("comparison").path("items").get(0).path("referenceAdultPriceVnd").asLong()).isEqualTo(2500000);assertThat(compare.result().path("criteria").path("region").asText()).isEqualTo("CENTRAL");
        var place=ask(token,plan(set(Field.DESTINATION,"Huế")),"Tôi muốn đi Huế");assertThat(place.result().path("criteria").path("destinationId").asInt()).isEqualTo(2);assertThat(place.result().path("tours").get(0).path("tourId").asInt()).isEqualTo(2);
        var empty=ask(token,plan(set(Field.MAX_BUDGET_VND,"1000000")),"Ngân sách chỉ 1 triệu");assertThat(empty.result().path("status").asText()).isEqualTo("NO_RESULTS");
        var clarify=ask(token,plan(set(Field.MAX_BUDGET_VND,"2000000"),new Change(Field.BUDGET_SCOPE,Action.CLEAR,"")),"Ngân sách 2 triệu, chưa rõ cho ai");assertThat(clarify.result().path("status").asText()).isEqualTo("CLARIFICATION");assertThat(clarify.result().path("clarification").path("reason").asText()).isEqualTo("BUDGET_SCOPE_REQUIRED");
        int beforeChoice=modelCalls.get();
        var scoped=ask(token,plan(set(Field.BUDGET_SCOPE,"PER_ADULT")),"Cho một người lớn");assertThat(scoped.result().path("criteria").path("destinationId").asInt()).isEqualTo(2);
        assertThat(scoped.result().path("criteria").path("budgetScope").asText()).isEqualTo("PER_ADULT");assertThat(modelCalls.get()).isEqualTo(beforeChoice);
        long version=scoped.result().path("turnVersion").asLong();catalogStatus.set(503);var backend=ask(token,plan(),"Tìm lại theo điều kiện hiện tại");assertThat(backend.status()).isEqualTo(503);assertThat(backend.json().path("error").path("type").asText()).isEqualTo("CATALOG_UNAVAILABLE");catalogStatus.set(200);
        var after=ask(token,plan(),"Tìm lại theo điều kiện hiện tại");assertThat(after.result().path("turnVersion").asLong()).isEqualTo(version+1);
        modelStatus.set(429);var quota=ask(token,plan(),"Tìm lại");assertThat(quota.status()).isEqualTo(429);int attempted=modelCalls.get();
        var cooling=post("/chat",Map.of("conversationId",token,"requestId",UUID.randomUUID().toString(),"message","Thử trong cooldown"));assertThat(cooling.status()).isEqualTo(429);assertThat(modelCalls.get()).isEqualTo(attempted);
        var reset=post("/conversations/reset",Map.of("conversationId",token));assertThat(reset.status()).isEqualTo(200);assertThat(reset.result().path("turnVersion").asInt()).isZero();assertThat(post("/chat",Map.of("conversationId",token,"requestId",UUID.randomUUID().toString(),"message","Phiên cũ")).status()).isEqualTo(410);
        assertThat(privateHeaderSeen).isFalse();assertThat(wrongProviderKey).isFalse();
        scenarioPassed=true;
    }
}
