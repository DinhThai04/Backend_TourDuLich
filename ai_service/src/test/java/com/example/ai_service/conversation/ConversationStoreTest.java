package com.example.ai_service.conversation;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static com.example.ai_service.conversation.ChatModels.*;

class ConversationStoreTest {
    static class Time extends Clock {
        Instant now=Instant.parse("2026-10-23T01:00:00Z");
        public ZoneId getZone() { return ZoneId.of("Asia/Ho_Chi_Minh"); }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
        void advance(Duration amount) { now=now.plus(amount); }
    }
    final Time time=new Time();
    final ConversationStore store=new ConversationStore(new SessionProperties(Duration.ofMinutes(30),2,10,20),time);
    static String id() { return UUID.randomUUID().toString(); }
    static Draft draft(Criteria c) { return new Draft(c,Hint.NONE,List.of(901,902),0,true,"RESULTS","Kết quả fixture",List.of(),null,null,false); }
    @Test void tokensAreRandomAndCapacityIsBounded() {
        var first=store.create(); var second=store.create();
        assertThat(first.conversationId()).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(second.conversationId());
        assertThat(first.expiresAt().getOffset()).isEqualTo(ZoneOffset.ofHours(7));
        assertThatThrownBy(store::create).isInstanceOfSatisfying(ChatFailure.class,failure->{
            assertThat(failure.type()).isEqualTo("AI_CAPACITY_REACHED");assertThat(failure.retryAfterSeconds()).isPositive();
        });
        assertThat(store.size()).isEqualTo(2);
    }
    @Test void expiryAndRestartReturnSameErrorAndCleanupReleasesCapacity() {
        var token=store.create().conversationId(); time.advance(Duration.ofMinutes(30));
        assertThatThrownBy(()->store.turn(token,id(),"hello",s->draft(s.criteria()))).hasMessage("CONVERSATION_EXPIRED");
        var restarted=new ConversationStore(new SessionProperties(Duration.ofMinutes(30),2,10,20),time);
        assertThatThrownBy(()->restarted.reset(token)).hasMessage("CONVERSATION_EXPIRED");
        assertThat(store.size()).isZero(); store.create();
    }
    @Test void cachedRetryDoesNotCallWorkOrRollbackNewerState() {
        var token=store.create().conversationId(); String request=id(); var calls=new AtomicInteger();
        var first=store.turn(token,request,"one",s->{ calls.incrementAndGet(); return draft(s.criteria()); });
        var next=new Criteria(Region.CENTRAL,null,null,3000000L,BudgetScope.PER_ADULT,null,null,null,SearchMode.BOOKABLE);
        store.turn(token,id(),"two",s->draft(next));
        var replay=store.turn(token,request,"one",s->{ throw new AssertionError("Work repeated"); });
        assertThat(replay).isSameAs(first); assertThat(replay.turnVersion()).isEqualTo(1);
        assertThat(store.snapshot(token).version()).isEqualTo(2); assertThat(store.snapshot(token).criteria()).isEqualTo(next);
        assertThat(calls.get()).isEqualTo(1);
        assertThatThrownBy(()->store.turn(token,request,"different",s->draft(s.criteria()))).hasMessage("REQUEST_ID_CONFLICT");
    }
    @Test void successfulTurnRefreshesTtlButRejectedRequestDoesNot() {
        var token=store.create().conversationId(); time.advance(Duration.ofMinutes(29));
        store.turn(token,id(),"valid",s->draft(s.criteria())); time.advance(Duration.ofMinutes(29));
        assertThatThrownBy(()->store.turn(token,id()," ",s->draft(s.criteria()))).hasMessage("INVALID_REQUEST");
        time.advance(Duration.ofMinutes(1));
        assertThatThrownBy(()->store.reset(token)).hasMessage("CONVERSATION_EXPIRED");
    }
    @Test void modelFailurePreservesStateAndReleasesBusyLease() {
        var token=store.create().conversationId(); store.turn(token,id(),"one",s->draft(s.criteria()));
        var before=store.snapshot(token); var request=id();
        assertThatThrownBy(()->store.turn(token,request,"two",s->{ throw ChatFailure.catalog(); })).hasMessage("CATALOG_UNAVAILABLE");
        assertThat(store.snapshot(token)).isEqualTo(before);
        assertThat(store.turn(token,request,"two",s->draft(s.criteria())).turnVersion()).isEqualTo(2);
    }
    @Test void sessionsNeverShareHistoryOrCriteria() {
        var a=store.create().conversationId(); var b=store.create().conversationId();
        var north=new Criteria(Region.NORTH,null,null,null,null,null,null,null,SearchMode.BOOKABLE);
        store.turn(a,id(),"north private text",s->draft(north));
        assertThat(store.snapshot(b).criteria()).isEqualTo(Criteria.empty());
        assertThat(store.snapshot(b).history()).isEmpty();
    }
    @Test void historyAndDedupeStayBounded() {
        var token=store.create().conversationId();
        for(int n=0;n<35;n++) store.turn(token,id(),"😀".repeat(700),s->draft(s.criteria()));
        assertThat(store.snapshot(token).history()).hasSize(10); assertThat(store.cacheSize(token)).isEqualTo(20);
        assertThat(store.snapshot(token).history().getFirst().user().codePointCount(0,store.snapshot(token).history().getFirst().user().length())).isEqualTo(600);
    }
    @Test void invalidTokenOrOversizedMessageNeverRunsWork() {
        var token=store.create().conversationId();
        assertThatThrownBy(()->store.turn("1",id(),"hello",s->{ throw new AssertionError(); })).hasMessage("INVALID_REQUEST");
        assertThatThrownBy(()->store.turn(token,id(),"😀".repeat(16001),s->{ throw new AssertionError(); })).hasMessage("INVALID_REQUEST");
        store.turn(token,id(),"😀".repeat(16000),s->draft(s.criteria()));
    }
    @Test void busyIsImmediateAndResetInvalidatesInFlightCommit() throws Exception {
        var token=store.create().conversationId(); var request=id();
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var running=pool.submit(()->store.turn(token,request,"one",s->{ entered.countDown();
                try { if(!release.await(3,TimeUnit.SECONDS)) throw new AssertionError("Test release timed out"); }
                catch(InterruptedException failure) { throw new RuntimeException(failure); } return draft(s.criteria()); }));
            try {
                assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(()->store.turn(token,id(),"two",s->draft(s.criteria()))).hasMessage("CONVERSATION_BUSY");
                assertThatThrownBy(()->store.turn(token,request,"one",s->draft(s.criteria()))).hasMessage("CONVERSATION_BUSY");
                var replacement=store.reset(token);
                release.countDown();
                assertThatThrownBy(()->running.get(3,TimeUnit.SECONDS)).hasCauseInstanceOf(ChatFailure.class);
                assertThat(store.snapshot(replacement.conversationId()).version()).isZero();
                assertThat(store.snapshot(replacement.conversationId()).history()).isEmpty();
                assertThatThrownBy(()->store.reset(token)).hasMessage("CONVERSATION_EXPIRED");
            } finally { release.countDown(); }
        }
    }
    @Test void workFinishingAfterTtlCannotRecreateExpiredEntry() {
        var token=store.create().conversationId();
        assertThatThrownBy(()->store.turn(token,id(),"one",s->{ time.advance(Duration.ofMinutes(31)); store.purge(); return draft(s.criteria()); }))
                .hasMessage("CONVERSATION_EXPIRED");
        assertThat(store.size()).isZero();
    }
    @Test void resetReusesCapacityAndStartsEmptyVersionZero() {
        var token=store.create().conversationId(); store.create(); store.turn(token,id(),"hello",s->draft(s.criteria()));
        var newSession=store.reset(token);
        assertThat(store.size()).isEqualTo(2); assertThat(newSession.turnVersion()).isZero();
        assertThat(newSession.conversationId()).isNotEqualTo(token);
    }
    @Test void rejectedAndOutOfScopeTurnsDoNotPoisonHistory() {
        var token=store.create().conversationId();var calls=new AtomicInteger();
        assertThatThrownBy(()->store.turn(token,id(),"SELECT * FROM users",s->{calls.incrementAndGet();return draft(s.criteria());})).hasMessage("AI_SCOPE_REJECTED");
        assertThat(calls).hasValue(0);assertThat(store.snapshot(token).version()).isZero();
        store.turn(token,id(),"Tell me about quantum mechanics",s->new Draft(s.criteria(),s.pending(),List.of(),0,false,"OUT_OF_SCOPE","SGU Tour only",List.of(),null,null,false));
        assertThat(store.snapshot(token).history()).isEmpty();
        store.turn(token,id(),"Tour miền Trung",s->draft(s.criteria()));assertThat(store.snapshot(token).history()).hasSize(1);
    }
    @Test void oneOwnerCannotConsumeTheWholeSessionPool() {
        var roomy=new ConversationStore(new SessionProperties(Duration.ofMinutes(30),10,10,20),time);
        for(int i=0;i<3;i++) roomy.create("owner-a");
        assertThatThrownBy(()->roomy.create("owner-a")).isInstanceOfSatisfying(ChatFailure.class,failure->{
            assertThat(failure.status()).isEqualTo(429);assertThat(failure.type()).isEqualTo("AI_SESSION_LIMIT");
            // The pool frees only when a session expires, so the header states that real window.
            assertThat(failure.retryAfterSeconds()).isEqualTo(Duration.ofMinutes(30).toSeconds());
        });
        assertThat(roomy.size()).isEqualTo(3);
        assertThat(roomy.create("owner-b").conversationId()).isNotBlank();
    }
}
