package com.example.notification_service.repository;

import com.example.notification_service.domain.MailDeliveryStatus;
import com.example.notification_service.domain.MailOutboxItem;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MailOutboxRepository {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public MailOutboxRepository(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
    }

    public boolean insert(String key, String eventType, String source, Integer booking,
            Integer account, String recipient, String payload, LocalDateTime due, LocalDateTime now) {
        try {
            return jdbc.update("""
                    INSERT INTO mail_outbox (event_key,event_type,source_service,booking_id,account_id,
                        recipient,payload,scheduled_at,status,next_attempt_at,created_at,updated_at)
                    VALUES (?,?,?,?,?,?,?,?,'PENDING',?,?,?)
                    """, key, eventType, source, booking, account, recipient, payload, due, due, now, now) == 1;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    public void recoverExpired(LocalDateTime now) {
        jdbc.update("""
                UPDATE mail_outbox SET status='UNKNOWN',last_error='Lease expired during SMTP dispatch',
                    claim_token=NULL,locked_until=NULL,updated_at=?
                WHERE status='DISPATCHING' AND locked_until<=?
                """, now, now);
        jdbc.update("""
                UPDATE mail_outbox SET status='PENDING',claim_token=NULL,locked_until=NULL,updated_at=?
                WHERE status='CLAIMED' AND locked_until<=?
                """, now, now);
    }

    public Optional<MailOutboxItem> claimNext(LocalDateTime now, LocalDateTime until) {
        return transactions.execute(tx -> {
            var ids = jdbc.queryForList("""
                    SELECT id FROM mail_outbox WHERE status IN ('PENDING','RETRY')
                      AND scheduled_at<=? AND next_attempt_at<=? ORDER BY next_attempt_at,id LIMIT 20
                    """, Long.class, now, now);
            for (long id : ids) {
                String token = UUID.randomUUID().toString();
                int won = jdbc.update("""
                        UPDATE mail_outbox SET status='CLAIMED',claim_token=?,locked_until=?,updated_at=?
                        WHERE id=? AND status IN ('PENDING','RETRY') AND scheduled_at<=? AND next_attempt_at<=?
                        """, token, until, now, id, now, now);
                if (won == 1) {
                    return Optional.of(jdbc.queryForObject("SELECT * FROM mail_outbox WHERE id=?",
                            (rs, row) -> new MailOutboxItem(rs.getLong("id"),
                                    rs.getString("event_type"),
                                    rs.getObject("booking_id", Integer.class),
                                    rs.getObject("account_id", Integer.class),
                                    rs.getString("recipient"), rs.getString("payload"),
                                    rs.getInt("attempt_count"), rs.getString("claim_token")), id));
                }
            }
            return Optional.empty();
        });
    }

    public boolean start(MailOutboxItem item, LocalDateTime now, LocalDateTime until) {
        return jdbc.update("""
                UPDATE mail_outbox SET status='DISPATCHING',attempt_count=attempt_count+1,
                    locked_until=?,updated_at=? WHERE id=? AND claim_token=? AND status='CLAIMED' AND locked_until>?
                """, until, now, item.id(), item.claimToken(), now) == 1;
    }

    public boolean finish(MailOutboxItem item, MailDeliveryStatus status, String reason,
            LocalDateTime retryAt, LocalDateTime now) {
        return jdbc.update("""
                UPDATE mail_outbox SET status=?,last_error=?,next_attempt_at=?,sent_at=?,
                    claim_token=NULL,locked_until=NULL,updated_at=?
                WHERE id=? AND claim_token=? AND status IN ('CLAIMED','DISPATCHING') AND locked_until>?
                """, status.name(), reason, retryAt, status == MailDeliveryStatus.SENT ? now : null,
                now, item.id(), item.claimToken(), now) == 1;
    }

    public List<Map<String, Object>> byBooking(int bookingId) {
        return jdbc.queryForList("""
                SELECT id,event_key,event_type,source_service,booking_id,account_id,recipient,status,
                    attempt_count,scheduled_at,sent_at,last_error,created_at,updated_at
                FROM mail_outbox WHERE booking_id=? ORDER BY id DESC LIMIT 30
                """, bookingId);
    }

    public boolean retry(int bookingId, long id, LocalDateTime now) {
        return jdbc.update("""
                UPDATE mail_outbox SET status='RETRY',attempt_count=0,next_attempt_at=?,updated_at=?,
                    last_error=NULL,claim_token=NULL,locked_until=NULL
                WHERE id=? AND booking_id=? AND status='FAILED' AND sent_at IS NULL
                """, now, now, id, bookingId) == 1;
    }

    public boolean hasAccountHistory(long accountId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM mail_outbox WHERE account_id=?", Integer.class, accountId);
        return count != null && count > 0;
    }
}
