package com.example.ai_service.guard;

import com.example.ai_service.config.BookingHelpProperties;
import com.example.ai_service.model.*;
import com.example.ai_service.conversation.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class UsageGuardTest {
    static class Time extends Clock {
        Instant value=Instant.parse("2026-10-23T08:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return this;}public Instant instant(){return value;}
        void add(long seconds){value=value.plusSeconds(seconds);}
    }
    static GuardProperties limits(int minute,int day,int tpm,int tpd) { return new GuardProperties(minute,day,tpm,tpd,11000,Duration.ofSeconds(1)); }
    @Test void minuteRequestWindowExpiresWithoutChargingRejectedCalls() {
        var time=new Time();var budget=new UsageBudget(limits(1,5,7000,150000),time);
        budget.reserve(100);
        assertThatThrownBy(()->budget.reserve(100)).isInstanceOfSatisfying(ModelFailure.class,e->{assertThat(e.type()).isEqualTo(ModelFailure.Type.AI_RATE_LIMITED);assertThat(e.retryAfterSeconds()).isEqualTo(60);});
        time.add(60);budget.reserve(100);
    }
    @Test void rollingDayBudgetDoesNotResetAtMidnightOrOnRetry() {
        var time=new Time();var budget=new UsageBudget(limits(8,1,7000,150000),time);budget.reserve(100);time.add(3600);
        assertThatThrownBy(()->budget.reserve(100)).isInstanceOfSatisfying(ModelFailure.class,
                e->{assertThat(e.type()).isEqualTo(ModelFailure.Type.AI_QUOTA_EXHAUSTED);
                // The header is truthful: the oldest reservation leaves the 24h window in 23h.
                assertThat(e.retryAfterSeconds()).isEqualTo(86400-3600);});
        time.add(86400);budget.reserve(100);
    }
    @Test void tokenQuotaRetryAfterSkipsReservationsThatCannotFreeEnoughRoom() {
        var time=new Time();var budget=new UsageBudget(limits(8,80,7000,2000),time);
        budget.reserve(100);time.add(60);budget.reserve(1900);time.add(60);
        assertThatThrownBy(()->budget.reserve(200)).isInstanceOfSatisfying(ModelFailure.class,
                e->{assertThat(e.type()).isEqualTo(ModelFailure.Type.AI_QUOTA_EXHAUSTED);
                // Dropping only the oldest 100 tokens is not enough, so the second window is the true one.
                assertThat(e.retryAfterSeconds()).isEqualTo(86400-60);});
    }
    @Test void tokensAreReservedBeforeCallAndUsageReconcilesExactlyOnce() {
        var time=new Time();var budget=new UsageBudget(limits(8,80,1000,10000),time);var first=budget.reserve(800);
        assertThatThrownBy(()->budget.reserve(300)).isInstanceOf(ModelFailure.class);
        budget.settle(first,100);budget.settle(first,1);budget.reserve(850);
        assertThatThrownBy(()->budget.reserve(100)).isInstanceOf(ModelFailure.class);
    }
    @Test void absentInvalidOrFailedUsageRetainsReservationAndOverageBlocksNextCall() {
        for(Integer usage:new Integer[]{null,-1,0,1000001,1200}) {
            var budget=new UsageBudget(limits(8,80,1000,10000),new Time());var first=budget.reserve(900);budget.settle(first,usage);
            assertThatThrownBy(()->budget.reserve(200)).isInstanceOf(ModelFailure.class);
        }
    }
    @Test void dayTokensAndSingleTooLargePromptHaveDifferentErrors() {
        var budget=new UsageBudget(limits(8,80,1000,1500),new Time());budget.reserve(900);
        assertThatThrownBy(()->budget.reserve(800)).isInstanceOfSatisfying(ModelFailure.class,e->assertThat(e.type()).isEqualTo(ModelFailure.Type.AI_QUOTA_EXHAUSTED));
        assertThatThrownBy(()->budget.reserve(1001)).isInstanceOfSatisfying(ModelFailure.class,e->assertThat(e.type()).isEqualTo(ModelFailure.Type.AI_INPUT_TOO_LARGE));
    }
    @Test void provider429CooldownCannotBeShortenedAndMissingRetryAfterWaits60s() {
        var time=new Time();var budget=new UsageBudget(limits(8,80,7000,150000),time);budget.coolDown(120);budget.coolDown(1);
        assertThatThrownBy(()->budget.reserve(1)).isInstanceOfSatisfying(ModelFailure.class,e->assertThat(e.retryAfterSeconds()).isEqualTo(120));
        time.add(120);budget.reserve(1);budget.coolDown(null);
        assertThatThrownBy(()->budget.reserve(1)).isInstanceOfSatisfying(ModelFailure.class,e->assertThat(e.retryAfterSeconds()).isEqualTo(60));
    }
    @Test void defaultsAcceptTheRealPlanContextButLargeInputStopsBeforeDelegate() throws Exception {
        var count=new AtomicInteger();
        var delegate=new LanguageModel(){ public <T> ModelResult<T> generate(ModelTask<T> task) {
            count.incrementAndGet();return new ModelResult<>(task.outputType().cast(new ChatModels.Plan(ChatModels.Intent.OUT_OF_SCOPE,false,java.util.List.of(),java.util.List.of(),ChatModels.Hint.NONE)),"test",1,100,20,120);
        }};
        var guarded=new BudgetedLanguageModel(delegate,new UsageBudget(GuardProperties.defaults(),new Time()),GuardProperties.defaults(),1024,"test");
        var interpreter=new ModelPlanInterpreter(guarded,new ObjectMapper().findAndRegisterModules(),new Time());
        assertThat(interpreter.interpret(ChatModels.State.empty(),"Tôi muốn đi miền Trung dưới ba triệu cho một người lớn.")).isNotNull();
        assertThatThrownBy(()->guarded.generate(new ModelTask<>("á".repeat(7800),"câu hỏi",ChatModels.Plan.class))).isInstanceOfSatisfying(ModelFailure.class,e->assertThat(e.type()).isEqualTo(ModelFailure.Type.AI_INPUT_TOO_LARGE));
        assertThat(count).hasValue(1);
    }
    @Test void traceContainsUsageButNoPromptAndPropagatesToWorker() throws Exception {
        var trace=new TurnTrace();var worker=new TurnExecutor(GuardProperties.defaults(),BookingHelpProperties.defaults());
        try(var ignored=TurnTrace.bind(trace)) {assertThat(worker.execute(()->TurnTrace.current())).isSameAs(trace);}
        finally {worker.close();}
        assertThat(TurnTrace.current()).isNull();
    }
    @Test void timeoutDoesNotReleaseCapacityWhileAnUncooperativeTaskStillRuns() throws Exception {
        var config=new GuardProperties(8,80,7000,150000,11000,Duration.ofMillis(200));
        var worker=new TurnExecutor(config,BookingHelpProperties.defaults());var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var caller=Executors.newSingleThreadExecutor();
        try {
            var response=caller.submit(()->worker.execute(()->{entered.countDown();while(release.getCount()>0){try{release.await();}catch(InterruptedException ignored){}}return "late";}));
            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->response.get(2,TimeUnit.SECONDS)).hasCauseInstanceOf(ModelFailure.class);
            assertThatThrownBy(()->worker.execute(()->"no overlap")).isInstanceOfSatisfying(ModelFailure.class,e->{assertThat(e.type()).isEqualTo(ModelFailure.Type.AI_CAPACITY_REACHED);assertThat(e.retryAfterSeconds()).isPositive();});
        } finally {release.countDown();worker.close();caller.shutdownNow();}
    }
    @Test void lanesKeepIndependentPermitsSoNeitherLaneStarvesTheOther() throws Exception {
        var config=new GuardProperties(8,80,7000,150000,11000,Duration.ofSeconds(5));
        var worker=new TurnExecutor(config,new BookingHelpProperties(1));
        var chatEntered=new CountDownLatch(1);var chatRelease=new CountDownLatch(1);
        var helpEntered=new CountDownLatch(1);var helpRelease=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var chat=pool.submit(()->worker.execute(TurnExecutor.Lane.CHAT,()->{chatEntered.countDown();await(chatRelease);return "chat";}));
            assertThat(chatEntered.await(2,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->worker.execute(TurnExecutor.Lane.CHAT,()->"busy")).isInstanceOfSatisfying(ModelFailure.class,e->{assertThat(e.type()).isEqualTo(ModelFailure.Type.AI_CAPACITY_REACHED);assertThat(e.retryAfterSeconds()).isPositive();});
            assertThat(worker.execute(TurnExecutor.Lane.BOOKING_HELP,()->"help")).isEqualTo("help");
            chatRelease.countDown();
            assertThat(chat.get(3,TimeUnit.SECONDS)).isEqualTo("chat");

            var help=pool.submit(()->worker.execute(TurnExecutor.Lane.BOOKING_HELP,()->{helpEntered.countDown();await(helpRelease);return "help";}));
            assertThat(helpEntered.await(2,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->worker.execute(TurnExecutor.Lane.BOOKING_HELP,()->"busy")).isInstanceOfSatisfying(ModelFailure.class,e->assertThat(e.type()).isEqualTo(ModelFailure.Type.AI_CAPACITY_REACHED));
            assertThat(worker.execute(TurnExecutor.Lane.CHAT,()->"chat")).isEqualTo("chat");
            helpRelease.countDown();
            assertThat(help.get(3,TimeUnit.SECONDS)).isEqualTo("help");
        } finally {chatRelease.countDown();helpRelease.countDown();worker.close();}
    }
    @Test void invalidLaneConfigurationFailsFast() {
        assertThatThrownBy(()->new BookingHelpProperties(0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->new BookingHelpProperties(9)).isInstanceOf(IllegalStateException.class);
    }
    @Test void invalidGuardConfigurationFailsFast() {
        assertThatThrownBy(()->limits(100,80,7000,150000)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->limits(8,80,9000,150000)).isInstanceOf(IllegalStateException.class);
    }
    @Test void oneModelCallPerTurnAndFiveHttpAttemptsCannotBeExceeded() throws Exception {
        var calls=new AtomicInteger();
        var delegate=new LanguageModel(){public <T> ModelResult<T> generate(ModelTask<T> task){calls.incrementAndGet();return new ModelResult<>(null,"test",1,null,null,null);}};
        var guarded=new BudgetedLanguageModel(delegate,new UsageBudget(GuardProperties.defaults(),new Time()),GuardProperties.defaults(),1024,"test");
        try(var ignored=TurnTrace.bind(new TurnTrace())) {
            var task=new ModelTask<>("system","question",String.class);guarded.generate(task);
            assertThatThrownBy(()->guarded.generate(task)).isInstanceOf(ModelFailure.class);assertThat(calls).hasValue(1);
            var requests=BoundedHttp.requests(java.net.http.HttpClient.newHttpClient(),Duration.ofSeconds(1));
            for(int i=0;i<5;i++) requests.createRequest(java.net.URI.create("http://127.0.0.1:1/fixture"),org.springframework.http.HttpMethod.GET);
            assertThatThrownBy(()->requests.createRequest(java.net.URI.create("http://127.0.0.1:1/fixture"),org.springframework.http.HttpMethod.GET)).isInstanceOf(ModelFailure.class);
        }
    }
    private static void await(CountDownLatch latch) {
        try { if(!latch.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("Test release timed out"); }
        catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw new IllegalStateException(interrupted); }
    }
}
