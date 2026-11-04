package com.example.api_gateway.config;

import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

/** Bounded per-peer admission. The peer comes from {@link ClientAddress}, which trusts proxy headers only from a local proxy. */
@Component
public final class AiTrafficLimiter {
    private static final int MAX_PEERS=1024;
    @org.springframework.beans.factory.annotation.Value("${app.ai.traffic.creates-per-30-minutes:20}") private int createLimit=20;
    @org.springframework.beans.factory.annotation.Value("${app.ai.traffic.chats-per-minute:6}") private int chatLimit=6;
    @org.springframework.beans.factory.annotation.Value("${app.ai.traffic.chats-per-day:40}") private int dailyLimit=40;
    @org.springframework.beans.factory.annotation.Value("${app.ai.traffic.resets-per-minute:10}") private int resetLimit=10;
    @org.springframework.beans.factory.annotation.Value("${app.ai.traffic.booking-help-per-minute:10}") private int bookingHelpLimit=10;
    @jakarta.annotation.PostConstruct void validate(){
        if(createLimit<1 || createLimit>50 || chatLimit<1 || chatLimit>30 || dailyLimit<1 || dailyLimit>1000 || resetLimit<1 || resetLimit>30
                || bookingHelpLimit<1 || bookingHelpLimit>60)
            throw new IllegalArgumentException("AI traffic limits outside supported range");
    }
    private final Clock clock;
    /** Access order keeps the eviction target deterministic once the peer map is full. */
    private final Map<String,Peer> peers=new LinkedHashMap<>(16,.75f,true);
    private static final class Peer {
        final Deque<Instant> creates=new ArrayDeque<>(),chats=new ArrayDeque<>(),dailyChats=new ArrayDeque<>(),resets=new ArrayDeque<>(),bookingHelp=new ArrayDeque<>();
        Instant touched;
    }
    public AiTrafficLimiter(){this(Clock.systemUTC());}
    AiTrafficLimiter(Clock clock){this.clock=clock;}
    private static void trim(Deque<Instant> events,Instant since){while(!events.isEmpty() && !events.peekFirst().isAfter(since))events.removeFirst();}
    private static long retry(Deque<Instant> events,int limit,Duration window,Instant now){
        trim(events,now.minus(window));return events.size()<limit?0:Math.max(1,Duration.between(now,events.peekFirst().plus(window)).getSeconds()+1);
    }
    public synchronized long admit(String peer,String action){
        Instant now=clock.instant();
        peers.entrySet().removeIf(e->!e.getValue().touched.isAfter(now.minus(Duration.ofDays(1))));
        var state=peers.get(peer);
        if(state==null){
            if(peers.size()>=MAX_PEERS){
                // Evict the least-recently-seen peer instead of locking every new client out.
                peers.remove(peers.keySet().iterator().next());
            }
            state=new Peer();state.touched=now;peers.put(peer,state);
        }
        long wait;
        switch(action){
            case "conversations" -> {wait=retry(state.creates,createLimit,Duration.ofMinutes(30),now);if(wait==0)state.creates.addLast(now);}
            case "chat" -> {
                wait=Math.max(retry(state.chats,chatLimit,Duration.ofMinutes(1),now),retry(state.dailyChats,dailyLimit,Duration.ofDays(1),now));
                if(wait==0){state.chats.addLast(now);state.dailyChats.addLast(now);}
            }
            case "conversations/reset" -> {wait=retry(state.resets,resetLimit,Duration.ofMinutes(1),now);if(wait==0)state.resets.addLast(now);}
            case "booking-help" -> {wait=retry(state.bookingHelp,bookingHelpLimit,Duration.ofMinutes(1),now);if(wait==0)state.bookingHelp.addLast(now);}
            default -> {return 0;}
        }
        state.touched=now;return wait;
    }
}
