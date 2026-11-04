package com.example.ai_service.conversation;

import com.example.ai_service.security.AiInputPolicy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import static com.example.ai_service.conversation.ChatModels.*;

/** One monitor guards admission/commit only. Model/network work never runs under this monitor. */
@Component
public final class ConversationStore {
    private static final class Entry {
        State state=State.empty();
        Instant expires;
        boolean busy;
        final String owner;
        final LinkedHashMap<String,Cached> cache=new LinkedHashMap<>();
        Entry(Instant expires, String owner) { this.expires=expires; this.owner=owner; }
    }
    private record Cached(String digest, ChatResult result) {}

    /** A single account may keep a few tabs open, but must not consume the whole shared session pool. */
    private static final int MAX_SESSIONS_PER_OWNER = 3;

    private final Map<String,Entry> entries=new HashMap<>();
    private final SecureRandom random=new SecureRandom();
    private final SessionProperties limits;
    private final Clock clock;
    public ConversationStore(SessionProperties limits, Clock clock) { this.limits=limits; this.clock=clock; }

    public synchronized Created create() { return create(null); }

    public synchronized Created create(String owner) {
        purge();
        if(entries.size()>=limits.maxSessions())
            throw new ChatFailure(503,"AI_CAPACITY_REACHED",retryAfter(entries.values().stream().map(entry->entry.expires).toList()));
        if(owner!=null && entries.values().stream().filter(entry->owner.equals(entry.owner)).count()>=MAX_SESSIONS_PER_OWNER)
            throw new ChatFailure(429,"AI_SESSION_LIMIT",retryAfter(entries.values().stream()
                    .filter(entry->owner.equals(entry.owner)).map(entry->entry.expires).toList()));
        return insert(owner);
    }
    /** Seconds until the earliest of these sessions frees capacity; the response header must be truthful. */
    private int retryAfter(List<Instant> expiries) {
        Instant now=clock.instant();
        Instant earliest=expiries.stream().min(Instant::compareTo).orElse(now);
        return (int)Math.max(1,Math.min(86400,(Duration.between(now,earliest).toMillis()+999)/1000));
    }
    public synchronized Created reset(String token) {
        validateToken(token); purge();
        Entry existing=entries.remove(token);
        if(existing==null) throw ChatFailure.expired();
        return insert(existing.owner);
    }
    private Created insert(String owner) {
        String token;
        do { byte[] bytes=new byte[32]; random.nextBytes(bytes); token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
        while(entries.containsKey(token));
        Instant expires=clock.instant().plus(limits.ttl());
        entries.put(token,new Entry(expires,owner));
        return new Created(token,0,at(expires));
    }
    public ChatResult turn(String token, String requestId, String message, Function<State,Draft> work) {
        validateToken(token);
        if(requestId == null || !requestId.matches(REQUEST_ID_PATTERN)) throw ChatFailure.invalid();
        AiInputPolicy.requireMessage(message);
        String request=UUID.fromString(requestId).toString();
        String text=message.strip();
        String digest=digest(text);
        Entry entry;
        State state;
        synchronized(this) {
            purge(); entry=entries.get(token);
            if(entry==null) throw ChatFailure.expired();
            Cached cached=entry.cache.get(request);
            if(cached!=null) {
                if(!cached.digest().equals(digest)) throw new ChatFailure(409,"REQUEST_ID_CONFLICT");
                return cached.result();
            }
            if(entry.busy) throw new ChatFailure(409,"CONVERSATION_BUSY");
            entry.busy=true; state=entry.state;
        }
        try {
            Draft draft=Objects.requireNonNull(work.apply(state));
            synchronized(this) {
                if(entries.get(token)!=entry || !entry.expires.isAfter(clock.instant())) {
                    entries.remove(token,entry); throw ChatFailure.expired();
                }
                Instant expiry=clock.instant().plus(limits.ttl());
                long version=state.version()+1;
                var result=new ChatResult(token,request,version,draft.status(),draft.reply(),draft.criteria(),
                        List.copyOf(draft.tours()),draft.clarification(),draft.detail(),draft.comparison(),draft.policy(),draft.summary(),at(expiry));
                var history=new ArrayList<>(draft.clearHistory()?List.<HistoryTurn>of():state.history());
                if(!"OUT_OF_SCOPE".equals(draft.status()) && AiInputPolicy.safeHistory(text))
                    history.add(new HistoryTurn(clip(text,600),clip(draft.reply(),400)));
                while(history.size()>limits.historyTurns()) history.removeFirst();
                entry.state=new State(draft.criteria(),draft.pending(),List.copyOf(draft.presentedIds()),draft.page(),
                        draft.hasMore(),List.copyOf(history),version);
                entry.cache.put(request,new Cached(digest,result));
                while(entry.cache.size()>limits.dedupeEntries()) entry.cache.remove(entry.cache.keySet().iterator().next());
                entry.expires=expiry;
                return result;
            }
        } finally { synchronized(this) { entry.busy=false; } }
    }
    @Scheduled(fixedDelay=60000,initialDelay=60000)
    public synchronized void purge() { entries.entrySet().removeIf(e->!e.getValue().expires.isAfter(clock.instant())); }
    synchronized int size() { purge(); return entries.size(); }
    synchronized State snapshot(String token) {
        purge(); var entry=entries.get(token); if(entry==null) throw ChatFailure.expired(); return entry.state;
    }
    synchronized int cacheSize(String token) {
        purge();
        var entry=entries.get(token);
        if(entry==null) throw ChatFailure.expired();
        return entry.cache.size();
    }
    private void validateToken(String token) { if(token == null || !token.matches(CONVERSATION_ID_PATTERN)) throw ChatFailure.invalid(); }
    private OffsetDateTime at(Instant instant) { return instant.atZone(clock.getZone()).toOffsetDateTime(); }
    static String clip(String text,int limit) {
        return text.codePointCount(0,text.length())<=limit?text:text.substring(0,text.offsetByCodePoints(0,limit));
    }
    private static String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
