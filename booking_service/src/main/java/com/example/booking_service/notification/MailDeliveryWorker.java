package com.example.booking_service.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailParseException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.LocalDateTime;

@Component
@ConditionalOnProperty(name = "app.mail.enabled", havingValue = "true")
public class MailDeliveryWorker {
    private static final Logger log = LoggerFactory.getLogger(MailDeliveryWorker.class);
    private final MailOutboxRepository repository;
    private final MailTemplateRenderer renderer;
    private final MailEligibility eligibility;
    private final SpringMailSender sender;
    private final MailProperties properties;
    private final Clock clock;
    public MailDeliveryWorker(MailOutboxRepository repository, MailTemplateRenderer renderer,
            MailEligibility eligibility, SpringMailSender sender, MailProperties properties, Clock clock) {
        this.repository = repository; this.renderer = renderer; this.eligibility = eligibility;
        this.sender = sender; this.properties = properties; this.clock = clock;
        properties.validate();
    }

    // The fallback must track application.yml: a stale number here would silently restore the old,
    // slower cadence for any profile that omits app.mail.
    @Scheduled(fixedDelayString = "${app.mail.poll-delay-ms:5000}")
    public void poll() {
        if (!properties.isEnabled()) return;
        try {
            repository.recoverExpired(now());
            for (int i = 0; i < properties.getBatchSize(); i++) {
                var item = repository.claimNext(now(), now().plus(properties.getLease()));
                if (item.isEmpty()) break;
                process(item.get());
            }
        } catch (Exception error) {
            // No SMTP error message/PII/credentials in logs. Missing migration cannot break booking requests.
            log.warn("Mail poll stopped ({}); check migration/configuration", error.getClass().getSimpleName());
        }
    }

    private void process(MailOutboxItem item) {
        MailTemplateRenderer.RenderedMail mail;
        try {
            MailOutboxService.validateAddress(item.recipient());
            if (properties.isTestMode() && !item.recipient().equalsIgnoreCase(properties.getTestRecipient())) {
                finish(item, MailDeliveryStatus.SKIPPED, "Recipient outside test mailbox", now());
                return;
            }
            var payload = renderer.decode(item);
            String obsolete = eligibility.skipReason(item, payload);
            if (obsolete != null) {
                finish(item, MailDeliveryStatus.SKIPPED, obsolete, now());
                return;
            }
            mail = renderer.render(item, payload);
        } catch (org.springframework.dao.DataAccessException databaseUnavailable) {
            // No SMTP attempted: leave CLAIMED to recover, rather than permanently losing this mail.
            log.warn("Mail eligibility unavailable for outbox {}", item.id());
            return;
        } catch (Exception invalid) {
            finish(item, MailDeliveryStatus.FAILED, "Invalid payload, address or template: " + invalid.getClass().getSimpleName(), now());
            return;
        }
        if (item.attemptCount() >= properties.getMaxAttempts()) {
            finish(item, MailDeliveryStatus.FAILED, "Attempt limit reached", now()); return;
        }
        if (!repository.start(item, now(), now().plus(properties.getLease()))) return;
        MailDeliveryStatus result = MailDeliveryStatus.SENT;
        String reason = null;
        LocalDateTime next = now();
        try {
            sender.send(item.recipient(), mail);
        } catch (MailAuthenticationException | MailPreparationException | MailParseException | IllegalArgumentException invalid) {
            result = MailDeliveryStatus.FAILED;
            reason = "SMTP configuration or message rejected before delivery: " + invalid.getClass().getSimpleName();
        } catch (Exception uncertain) {
            if (connectionNeverOpened(uncertain)) {
                int attempts = item.attemptCount() + 1;
                result = attempts >= properties.getMaxAttempts() ? MailDeliveryStatus.FAILED : MailDeliveryStatus.RETRY;
                next = now().plusMinutes(attempts == 1 ? 10 : 30);
                reason = "SMTP connection could not be opened";
            } else {
                result = MailDeliveryStatus.UNKNOWN;
                reason = "SMTP outcome uncertain; manual review required: " + uncertain.getClass().getSimpleName();
            }
        }
        // Separate from SMTP catch: a failed DB acknowledgement must never cause a second send.
        finish(item, result, reason, next);
    }

    private static boolean connectionNeverOpened(Throwable error) {
        // A MailSendException can contain per-message outcomes, including partial delivery.
        // Retry only a connection-level failure with NO per-message result.
        if (error instanceof org.springframework.mail.MailSendException send
                && !send.getFailedMessages().isEmpty()) return false;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.ConnectException || cause instanceof java.net.UnknownHostException) return true;
        }
        return false; // socket read/write timeouts may occur after SMTP accepted DATA
    }

    private void finish(MailOutboxItem item, MailDeliveryStatus result, String reason, LocalDateTime next) {
        if (!repository.finish(item, result, reason, next, now()))
            log.warn("Mail acknowledgement rejected for stale lease on outbox {}", item.id());
    }
    private LocalDateTime now() { return LocalDateTime.now(clock); }
}
