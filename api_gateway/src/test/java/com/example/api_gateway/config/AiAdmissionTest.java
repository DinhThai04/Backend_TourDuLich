package com.example.api_gateway.config;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import java.time.*;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class AiAdmissionTest {
    static class Time extends Clock {
        Instant now=Instant.parse("2026-10-27T00:00:00Z");public Instant instant(){return now;}public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}
    }
    @Test void onePeerCannotFillAllSessionSlotsAndWindowExpires(){
        var time=new Time();var limiter=new AiTrafficLimiter(time);
        for(int i=0;i<20;i++)assertEquals(0,limiter.admit("peer-one","conversations"));
        assertTrue(limiter.admit("peer-one","conversations")>0);assertEquals(0,limiter.admit("peer-two","conversations"));
        time.now=time.now.plusSeconds(1801);assertEquals(0,limiter.admit("peer-one","conversations"));
    }
    @Test void chatAndResetHaveIndependentBoundedBudgets(){
        var time=new Time();var limiter=new AiTrafficLimiter(time);
        for(int i=0;i<6;i++)assertEquals(0,limiter.admit("one","chat"));assertTrue(limiter.admit("one","chat")>0);
        assertEquals(0,limiter.admit("one","conversations/reset"));time.now=time.now.plusSeconds(61);assertEquals(0,limiter.admit("one","chat"));
    }
    @Test void bookingHelpHasItsOwnMinuteBudgetAndDoesNotBlockChat(){
        var time=new Time();var limiter=new AiTrafficLimiter(time);
        for(int i=0;i<10;i++)assertEquals(0,limiter.admit("one","booking-help"));
        assertTrue(limiter.admit("one","booking-help")>0);
        assertEquals(0,limiter.admit("one","chat"));
        time.now=time.now.plusSeconds(61);
        assertEquals(0,limiter.admit("one","booking-help"));
    }
    @Test void bookingHelpIsForwardedUntilItsBudgetIsExhaustedWhileChatStillForwards(){
        var filter=new AiAdmissionFilter(new AiTrafficLimiter(),"/api/v1");var forwarded=new AtomicInteger();
        for(int i=0;i<10;i++){
            var exchange=MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/ai/booking-help")
                    .remoteAddress(new InetSocketAddress("203.0.113.30",40000+i)).body("{}"));
            filter.filter(exchange,e->{forwarded.incrementAndGet();return reactor.core.publisher.Mono.empty();}).block();
            assertNull(exchange.getResponse().getStatusCode());
        }
        var blocked=MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/ai/booking-help")
                .remoteAddress(new InetSocketAddress("203.0.113.30",41000)).body("{}"));
        filter.filter(blocked,e->{forwarded.incrementAndGet();return reactor.core.publisher.Mono.empty();}).block();
        assertEquals(429,blocked.getResponse().getStatusCode().value());
        assertNotNull(blocked.getResponse().getHeaders().getFirst("Retry-After"));
        assertEquals(10,forwarded.get());

        // The same caller can still use chat: the booking-help bucket is separate.
        var chat=MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/ai/chat")
                .remoteAddress(new InetSocketAddress("203.0.113.30",42000)).body("{}"));
        filter.filter(chat,e->{forwarded.incrementAndGet();return reactor.core.publisher.Mono.empty();}).block();
        assertNull(chat.getResponse().getStatusCode());
        assertEquals(11,forwarded.get());
    }
    @Test void spoofedHeadersCannotChangePeerAndRejectedRequestsNeverReachUpstream(){
        var filter=new AiAdmissionFilter(new AiTrafficLimiter(),"/api/v1");var forwarded=new AtomicInteger();
        for(int i=0;i<21;i++){
            var request=MockServerHttpRequest.post("/api/v1/ai/conversations").remoteAddress(new InetSocketAddress("203.0.113.7",40000+i))
                    .header("X-Forwarded-For","198.51.100."+i).header("X-Real-IP","198.51.100."+i)
                    .header("Forwarded","for=198.51.100."+i).body("{}");
            var exchange=MockServerWebExchange.from(request);
            filter.filter(exchange,e->{forwarded.incrementAndGet();return reactor.core.publisher.Mono.empty();}).block();
            if(i==20){assertEquals(429,exchange.getResponse().getStatusCode().value());assertNotNull(exchange.getResponse().getHeaders().getFirst("Retry-After"));}
        }
        assertEquals(20,forwarded.get());
    }

    @Test void trustedProxyBudgetsAndOwnerFollowTheRealClientAddress(){
        var filter=new AiAdmissionFilter(new AiTrafficLimiter(),"/api/v1");
        var owners=new java.util.ArrayList<String>();
        for(int i=0;i<20;i++){
            var exchange=MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/ai/conversations")
                    .remoteAddress(new InetSocketAddress("10.0.0.5",40000+i))
                    .header("X-Real-IP","203.0.113.9").body("{}"));
            filter.filter(exchange,e->{owners.add(e.getRequest().getHeaders().getFirst(AiAdmissionFilter.OWNER_HEADER));
                return reactor.core.publisher.Mono.empty();}).block();
        }
        var other=MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/ai/conversations")
                .remoteAddress(new InetSocketAddress("10.0.0.5",41000))
                .header("X-Real-IP","203.0.113.10").body("{}"));
        filter.filter(other,e->{owners.add(e.getRequest().getHeaders().getFirst(AiAdmissionFilter.OWNER_HEADER));
            return reactor.core.publisher.Mono.empty();}).block();
        var blocked=MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/ai/conversations")
                .remoteAddress(new InetSocketAddress("10.0.0.5",42000))
                .header("X-Real-IP","203.0.113.9").body("{}"));
        filter.filter(blocked,e->reactor.core.publisher.Mono.empty()).block();
        assertEquals("p:203.0.113.9",owners.getFirst());
        assertEquals("p:203.0.113.10",owners.getLast());
        assertEquals(429,blocked.getResponse().getStatusCode().value());
    }
    @Test void catalogAndPreflightAreNotCharged(){
        var limiter=new AiTrafficLimiter();var filter=new AiAdmissionFilter(limiter,"/api/v1");var count=new AtomicInteger();
        for(int i=0;i<10;i++)filter.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/ai/policies")),e->{count.incrementAndGet();return reactor.core.publisher.Mono.empty();}).block();
        assertEquals(10,count.get());assertEquals(0,limiter.admit("unknown","conversations"));
    }
    @Test void dailyChatBudgetAndPeerMapAreBounded(){
        var time=new Time();var limiter=new AiTrafficLimiter(time);
        for(int i=0;i<40;i++){if(i%6==0)time.now=time.now.plusSeconds(61);assertEquals(0,limiter.admit("daily","chat"));}
        time.now=time.now.plusSeconds(61);assertTrue(limiter.admit("daily","chat")>0);
        var bounded=new AiTrafficLimiter(time);
        // Exhaust peer-0's budget first so its eviction from the full map is observable.
        for(int i=0;i<20;i++)assertEquals(0,bounded.admit("peer-0","conversations"));
        assertTrue(bounded.admit("peer-0","conversations")>0);
        for(int i=1;i<1024;i++)assertEquals(0,bounded.admit("peer-"+i,"conversations"));
        // A full map evicts the least-recently-seen peer instead of locking new clients out.
        assertEquals(0,bounded.admit("overflow","conversations"));
        assertEquals(0,bounded.admit("peer-0","conversations"));
        time.now=time.now.plusSeconds(86401);assertEquals(0,bounded.admit("overflow","conversations"));
    }

    @Test void ownerHeaderIsDerivedFromCallerAndSpoofedValuesAreReplaced(){
        var filter=new AiAdmissionFilter(new AiTrafficLimiter(),"/api/v1");
        var owners=new java.util.ArrayList<String>();
        filter.filter(MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/ai/chat")
                        .remoteAddress(new InetSocketAddress("192.0.2.7",5000))
                        .header("X-Ai-Owner","attacker-chosen").body("{}")),
                e->{var supplied=e.getRequest().getHeaders().get(AiAdmissionFilter.OWNER_HEADER);
                    owners.add(supplied.getFirst());assertFalse(supplied.contains("attacker-chosen"));
                    return reactor.core.publisher.Mono.empty();}).block();
        filter.filter(MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/ai/chat")
                        .remoteAddress(new InetSocketAddress("192.0.2.7",5001))
                        .header("Authorization","Bearer fixture.jwt.token").body("{}")),
                e->{owners.add(e.getRequest().getHeaders().getFirst(AiAdmissionFilter.OWNER_HEADER));
                    return reactor.core.publisher.Mono.empty();}).block();
        assertEquals("p:192.0.2.7",owners.get(0));
        assertTrue(owners.get(1).matches("t:[0-9a-f]{64}"),owners.get(1));
        assertNotEquals(owners.get(0),owners.get(1));
    }
}
