package com.example.booking_service.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.time.LocalDateTime;

@Service
public class ReminderPlanner {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ReminderPlanner.class);
    public record Request(boolean dryRun, int batchSize, int afterBookingId) {
        public Request {
            if (batchSize < 1 || batchSize > 200 || afterBookingId < 0) throw new IllegalArgumentException("batchSize: 1..200; afterBookingId >= 0");
        }
        public static Request preview() { return new Request(true,100,0); }
    }
    public record Result(boolean dryRun, int scanned, int eligible, int created, int alreadyExists,
            int skipped, int failed, int nextCursor, boolean hasMore, int invalidRecipients) {}
    private final JdbcTemplate jdbc;
    private final MailOutboxService outbox;
    private final MailProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final ReminderStateReader reader;
    private final ReminderRules rules = new ReminderRules();
    public ReminderPlanner(JdbcTemplate jdbc, MailOutboxService outbox, MailProperties properties, Clock clock, PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.outbox=outbox; this.properties=properties; this.clock=clock;
        this.transactions=new TransactionTemplate(manager); reader=new ReminderStateReader(jdbc);
    }
    public Result dispatch(Request request) {
        LocalDateTime now=LocalDateTime.now(clock).withNano(0);
        // Keyset pagination scans every eligible booking, not only the first batch every hour.
        var ids=jdbc.queryForList("""
            SELECT b.id FROM booking b JOIN tour_departure d ON d.id=b.tour_departure_id
            WHERE b.id>? AND b.status IN ('PENDING_PAYMENT','CONFIRMED') AND d.departure_date>? ORDER BY b.id LIMIT ?
            """,Integer.class,request.afterBookingId(),now.toLocalDate().atStartOfDay(),request.batchSize()+1);
        boolean more=ids.size()>request.batchSize();
        int count=Math.min(ids.size(),request.batchSize()),eligible=0,created=0,exists=0,skipped=0,failed=0,invalidRecipients=0,cursor=request.afterBookingId();
        for(int index=0;index<count;index++) {
            int id=ids.get(index); cursor=id;
            try {
                String outcome=transactions.execute(tx -> {
                    var state=reader.read(id);
                    if(state.isEmpty()) return "skip";
                    var s=state.get();
                    var selected=rules.select(s,now);
                    if(selected.isEmpty()) return "skip";
                    if(properties.isTestMode() && !properties.getTestRecipient().equalsIgnoreCase(s.recipient())) return "skip";
                    try { MailOutboxService.validateAddress(s.recipient()); }
                    catch (IllegalArgumentException invalidAddress) { return "invalid_recipient"; }
                    var choice=selected.get();
                    if(request.dryRun()) return "preview";
                    var payload=new MailPayload(1,s.name(),s.tour(),s.departureId(),s.departureAt(),s.passengers(),
                            null,null,null,choice.deadline(),choice.daysBefore(),choice.due());
                    return outbox.enqueue(rules.key(s,choice),choice.type(),"n8n_planner",s.bookingId(),s.accountId(),
                            s.recipient(),payload,now) ? "created" : "exists";
                });
                if("invalid_recipient".equals(outcome)) {
                    skipped++;invalidRecipients++;
                    log.warn("Reminder skipped for booking {} (invalid recipient)",id);
                }
                else if("skip".equals(outcome)) skipped++;
                else { eligible++; if("created".equals(outcome)) created++; else if("exists".equals(outcome)) exists++; }
            } catch(Exception error) {
                if(error instanceof org.springframework.dao.DataAccessException databaseFailure) throw databaseFailure;
                log.warn("Reminder planning failed for booking {} ({})",id,error.getClass().getSimpleName());
                failed++; // no PII, credential or raw exception message in API output
            }
        }
        return new Result(request.dryRun(),count,eligible,created,exists,skipped,failed,cursor,more,invalidRecipients);
    }
}
