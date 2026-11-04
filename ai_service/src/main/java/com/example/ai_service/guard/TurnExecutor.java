package com.example.ai_service.guard;

import com.example.ai_service.config.BookingHelpProperties;
import com.example.ai_service.model.ModelFailure;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Independent request lanes. Each lane has its own worker permits, no waiting queue and the same
 * whole-turn deadline, so a slow chat turn cannot starve booking-help and vice versa. A timed-out
 * worker retains its lane permit until it really exits.
 */
@Component
public final class TurnExecutor {
    public enum Lane { CHAT, BOOKING_HELP }
    private static final ThreadLocal<Long> DEADLINE=new ThreadLocal<>();
    private static final long NANOS_PER_SECOND=1_000_000_000L;

    private static final class Worker {
        private final ThreadPoolExecutor pool;
        private final ConcurrentHashMap<Long,Integer> activeDeadlines=new ConcurrentHashMap<>();
        private Worker(int permits,String name) {
            pool=new ThreadPoolExecutor(permits,permits,0,TimeUnit.MILLISECONDS,new SynchronousQueue<>(),task->{
                var thread=new Thread(task,name);thread.setDaemon(true);return thread;
            },new ThreadPoolExecutor.AbortPolicy());
        }
        private void begin(long deadline){ activeDeadlines.merge(deadline,1,Integer::sum); }
        private void end(long deadline){ activeDeadlines.computeIfPresent(deadline,(key,count)->count<=1?null:count-1); }
        /** Saturation lasts no longer than the latest running turn's own deadline. */
        private int retryAfterSeconds() {
            long latest=activeDeadlines.keySet().stream().mapToLong(Long::longValue).max().orElse(System.nanoTime());
            long remaining=latest-System.nanoTime();
            return (int)Math.max(1,(remaining+NANOS_PER_SECOND-1)/NANOS_PER_SECOND);
        }
    }

    private final GuardProperties limits;
    private final Map<Lane,Worker> lanes;
    public TurnExecutor(GuardProperties limits,BookingHelpProperties bookingHelp) {
        this.limits=limits;
        this.lanes=Map.of(Lane.CHAT,new Worker(1,"sgu-ai-turn"),
                Lane.BOOKING_HELP,new Worker(bookingHelp.maxConcurrent(),"sgu-ai-booking-help"));
    }
    public <T> T execute(Supplier<T> task) { return execute(Lane.CHAT,task); }
    public <T> T execute(Lane lane,Supplier<T> task) {
        var worker=Objects.requireNonNull(lanes.get(lane),"Unknown turn lane");
        long deadline=System.nanoTime()+limits.turnTimeout().toNanos();
        var trace=TurnTrace.current(); Future<T> future;
        try {
            future=worker.pool.submit(()->{
                worker.begin(deadline);
                DEADLINE.set(deadline);
                try(var scope=TurnTrace.bind(trace)) {
                    try { var result=task.get();check();return result; }
                    catch(RuntimeException failure) { check();throw failure; }
                } finally { worker.end(deadline);DEADLINE.remove(); }
            });
        } catch(RejectedExecutionException busy) { throw new ModelFailure(ModelFailure.Type.AI_CAPACITY_REACHED,worker.retryAfterSeconds()); }
        try { return future.get(limits.turnTimeout().toMillis(),TimeUnit.MILLISECONDS); }
        catch(TimeoutException timeout) { future.cancel(true);throw new ModelFailure(ModelFailure.Type.AI_TIMEOUT); }
        catch(InterruptedException interrupted) { future.cancel(true);Thread.currentThread().interrupt();throw new ModelFailure(ModelFailure.Type.AI_TIMEOUT); }
        catch(ExecutionException failure) {
            if(failure.getCause() instanceof RuntimeException known) throw known;
            throw new ModelFailure(ModelFailure.Type.AI_PROVIDER_UNAVAILABLE);
        }
    }
    public static void check() {
        var deadline=DEADLINE.get();
        if(deadline!=null && (Thread.currentThread().isInterrupted() || System.nanoTime()>=deadline))
            throw new ModelFailure(ModelFailure.Type.AI_TIMEOUT);
    }
    public static Duration remaining(Duration maximum) {
        check();var deadline=DEADLINE.get();
        return deadline==null?maximum:Duration.ofNanos(Math.max(1000000,Math.min(maximum.toNanos(),deadline-System.nanoTime())));
    }
    /** Whole seconds until this turn's deadline, or 0 when the caller carries no deadline. */
    public static int remainingSeconds() {
        var deadline=DEADLINE.get();
        return deadline==null?0:(int)Math.max(0,Math.min(86400,(deadline-System.nanoTime())/NANOS_PER_SECOND));
    }
    @PreDestroy public void close() { lanes.values().forEach(worker->worker.pool.shutdownNow()); }
}
