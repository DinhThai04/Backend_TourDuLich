package com.example.notification_service.service;

import com.example.notification_service.config.MailAddress;
import com.example.notification_service.config.MailProperties;
import com.example.notification_service.domain.MailDeliveryStatus;
import com.example.notification_service.domain.MailOutboxItem;
import com.example.notification_service.domain.MailPayload;
import com.example.notification_service.repository.MailOutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

@Component
@ConditionalOnProperty(name = "app.mail.enabled", havingValue = "true")
public class MailDeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(MailDeliveryWorker.class);

    private final MailOutboxRepository repository;
    private final MailSender sender;
    private final MailProperties properties;
    private final ObjectMapper mapper;
    private final Clock clock;

    public MailDeliveryWorker(MailOutboxRepository repository, MailSender sender,
            MailProperties properties, ObjectMapper mapper, Clock clock) {
        this.repository = repository;
        this.sender = sender;
        this.properties = properties;
        this.mapper = mapper;
        this.clock = clock;
        properties.validate();
    }

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
            log.warn("Mail poll stopped ({}); check migration/configuration", error.getClass().getSimpleName());
        }
    }

    private void process(MailOutboxItem item) {
        MailPayload payload;
        try {
            MailAddress.validate(item.recipient());
            if (properties.isTestMode() && !item.recipient().equalsIgnoreCase(properties.getTestRecipient())) {
                finish(item, MailDeliveryStatus.SKIPPED, "Recipient outside test mailbox", now());
                return;
            }
            payload = mapper.readValue(item.payload(), MailPayload.class);
            if (payload.version() != 1 || payload.subject() == null || payload.html() == null)
                throw new IllegalArgumentException("Invalid mail payload");
        } catch (Exception invalid) {
            finish(item, MailDeliveryStatus.FAILED,
                    "Invalid payload, address or template: " + invalid.getClass().getSimpleName(), now());
            return;
        }
        if (item.attemptCount() >= properties.getMaxAttempts()) {
            finish(item, MailDeliveryStatus.FAILED, "Attempt limit reached", now());
            return;
        }
        if (!repository.start(item, now(), now().plus(properties.getLease()))) return;
        MailDeliveryStatus result = MailDeliveryStatus.SENT;
        String reason = null;
        LocalDateTime next = now();
        try {
            sender.send(item.recipient(), payload);
        } catch (MailAuthenticationException | MailPreparationException | MailParseException
                 | IllegalArgumentException invalid) {
            result = MailDeliveryStatus.FAILED;
            reason = "SMTP configuration or message rejected before delivery: "
                    + invalid.getClass().getSimpleName();
        } catch (Exception uncertain) {
            if (connectionNeverOpened(uncertain)) {
                int attempts = item.attemptCount() + 1;
                result = attempts >= properties.getMaxAttempts()
                        ? MailDeliveryStatus.FAILED : MailDeliveryStatus.RETRY;
                next = now().plusMinutes(attempts == 1 ? 10 : 30);
                reason = "SMTP connection could not be opened";
            } else {
                result = MailDeliveryStatus.UNKNOWN;
                reason = "SMTP outcome uncertain; manual review required: "
                        + uncertain.getClass().getSimpleName();
            }
        }
        finish(item, result, reason, next);
    }

    private static boolean connectionNeverOpened(Throwable error) {
        if (error instanceof org.springframework.mail.MailSendException send
                && !send.getFailedMessages().isEmpty()) return false;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.ConnectException || cause instanceof java.net.UnknownHostException)
                return true;
        }
        return false;
    }

    private void finish(MailOutboxItem item, MailDeliveryStatus result, String reason, LocalDateTime next) {
        if (!repository.finish(item, result, reason, next, now()))
            log.warn("Mail acknowledgement rejected for stale lease on outbox {}", item.id());
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
