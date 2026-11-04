package com.example.ai_service.guard;

import com.example.ai_service.model.ModelFailure;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/** Process-local rolling 60s/24h admission, not a claim about the provider's shared account quota. */
@Component
public class UsageBudget {
    private static final int MAX_VALID_ACTUAL_TOKENS=1_000_000;

    public static final class Reservation {
        private final Instant at;
        private int tokens;
        private boolean settled;
        private Reservation(Instant at,int tokens) { this.at=at;this.tokens=tokens; }
    }
    private final GuardProperties limits;
    private final Clock clock;
    private final Deque<Reservation> attempts=new ArrayDeque<>();
    private Instant coolingUntil=Instant.EPOCH;
    public UsageBudget(GuardProperties limits,Clock clock) { this.limits=limits;this.clock=clock; }
    public synchronized Reservation reserve(int estimatedTokens) {
        Instant now=clock.instant(); attempts.removeIf(a->!a.at.isAfter(now.minus(Duration.ofDays(1))));
        if(coolingUntil.isAfter(now)) throw rate(seconds(now,coolingUntil));
        if(estimatedTokens<1 || estimatedTokens>limits.tokensPerMinute())
            throw new ModelFailure(ModelFailure.Type.AI_INPUT_TOO_LARGE);
        long dayTokens=attempts.stream().mapToLong(a->a.tokens).sum();
        if(attempts.size()>=limits.requestsPerDay() || dayTokens+estimatedTokens>limits.tokensPerDay())
            throw new ModelFailure(ModelFailure.Type.AI_QUOTA_EXHAUSTED,dayWait(now,dayTokens,attempts.size(),estimatedTokens));
        var minute=attempts.stream().filter(a->a.at.isAfter(now.minusSeconds(60))).toList();
        if(minute.size()>=limits.requestsPerMinute() || minute.stream().mapToLong(a->a.tokens).sum()+estimatedTokens>limits.tokensPerMinute())
            throw rate(minute.isEmpty()?60:seconds(now,minute.getFirst().at.plusSeconds(60)));
        var reservation=new Reservation(now,estimatedTokens); attempts.addLast(reservation); return reservation;
    }
    public synchronized void settle(Reservation reservation,Integer actualTotal) {
        if(reservation.settled) return;
        reservation.settled=true;
        // Missing/invalid usage keeps the full reservation; failed attempts are never refunded.
        if(actualTotal!=null && actualTotal>0 && actualTotal<=MAX_VALID_ACTUAL_TOKENS) reservation.tokens=actualTotal;
    }
    public synchronized void coolDown(Integer seconds) {
        Instant until=clock.instant().plusSeconds(seconds==null?60:Math.max(1,Math.min(86400,seconds)));
        if(until.isAfter(coolingUntil)) coolingUntil=until;
    }
    private static int seconds(Instant from,Instant to) { return (int)Math.max(1,Math.min(86400,(Duration.between(from,to).toMillis()+999)/1000)); }
    /**
     * Seconds until enough of the rolling 24h window has expired for this reservation to fit.
     * Attempts are appended in time order, so the first one that frees both request and token room wins.
     */
    private int dayWait(Instant now,long tokens,int count,int estimatedTokens) {
        for(var attempt:attempts) {
            tokens-=attempt.tokens; count--;
            if(count<limits.requestsPerDay() && tokens+estimatedTokens<=limits.tokensPerDay())
                return seconds(now,attempt.at.plus(Duration.ofDays(1)));
        }
        return 86400;
    }
    private static ModelFailure rate(int wait) { return new ModelFailure(ModelFailure.Type.AI_RATE_LIMITED,wait); }
}
