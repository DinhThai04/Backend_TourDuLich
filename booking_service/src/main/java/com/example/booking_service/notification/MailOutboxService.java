package com.example.booking_service.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDateTime;

@Service
public class MailOutboxService {
    private final MailOutboxRepository repository;
    private final ObjectMapper mapper;
    private final Clock clock;

    public MailOutboxService(MailOutboxRepository repository, ObjectMapper mapper, Clock clock) {
        this.repository = repository;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Joins the producer transaction. Enqueueing is independent of whether SMTP is enabled. */
    @Transactional
    public boolean enqueue(String key, MailEventType type, String source, Integer booking,
            Integer account, String recipient, MailPayload payload, LocalDateTime due) {
        if (key == null || !key.matches("[A-Za-z0-9:._-]{1,191}")
                || source == null || !source.matches("[A-Za-z0-9_-]{1,40}")
                || type == null || payload == null || payload.version() != 1 || due == null) {
            throw new IllegalArgumentException("Invalid mail event");
        }
        validateAddress(recipient);
        if (type != MailEventType.TEST_MAIL && (booking == null || booking < 1 || account == null || account < 1)) {
            throw new IllegalArgumentException("Business mail requires booking and account");
        }
        try {
            return repository.insert(key, type, source, booking, account, recipient,
                    mapper.writeValueAsString(payload), due, LocalDateTime.now(clock));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot encode mail payload", e);
        }
    }

    public static void validateAddress(String value) {
        try {
            if (value == null || value.length() > 254 || value.contains("\r") || value.contains("\n"))
                throw new AddressException("Invalid email");
            InternetAddress address = new InternetAddress(value, true);
            address.validate();
            if (!value.equals(address.getAddress()) || address.getPersonal() != null)
                throw new AddressException("Expected a single plain email address");
        } catch (AddressException e) {
            throw new IllegalArgumentException("Invalid recipient or sender email");
        }
    }
}
