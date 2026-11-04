package com.example.booking_service.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
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

    public boolean insert(String key, MailEventType type, String source, Integer booking,
            Integer account, String recipient, String payload, LocalDateTime due, LocalDateTime now) {
        // UNIQUE key is the arbiter for concurrent producers. Do not hide unrelated SQL errors.
        try {
            return jdbc.update("""
                INSERT INTO mail_outbox (event_key,event_type,source_service,booking_id,account_id,
                    recipient,payload,scheduled_at,status,next_attempt_at,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,'PENDING',?,?,?)
                """, key, type.name(), source, booking, account, recipient, payload, due, due, now, now) == 1;
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
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
        // Optimistic CAS makes two workers safe without depending on SKIP LOCKED support.
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
                            MailEventType.valueOf(rs.getString("event_type")),
                            rs.getObject("booking_id", Integer.class), rs.getObject("account_id", Integer.class),
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
        // Never accept a stale token or overwrite another worker's decision.
        return jdbc.update("""
            UPDATE mail_outbox SET status=?,last_error=?,next_attempt_at=?,sent_at=?,
                claim_token=NULL,locked_until=NULL,updated_at=?
            WHERE id=? AND claim_token=? AND status IN ('CLAIMED','DISPATCHING') AND locked_until>?
            """, status.name(), reason, retryAt, status == MailDeliveryStatus.SENT ? now : null,
                now, item.id(), item.claimToken(), now) == 1;
    }
}
