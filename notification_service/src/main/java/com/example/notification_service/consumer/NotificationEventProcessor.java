package com.example.notification_service.consumer;

import com.example.notification_service.config.MailAddress;
import com.example.notification_service.domain.MailPayload;
import com.example.notification_service.repository.MailOutboxRepository;
import com.example.notification_service.service.MailTemplateService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
public class NotificationEventProcessor {

    private static final String CONSUMER_ID = "notification-mail-v1";

    private final JdbcTemplate jdbc;
    private final MailOutboxRepository outbox;
    private final MailTemplateService templates;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public NotificationEventProcessor(JdbcTemplate jdbc, MailOutboxRepository outbox,
                                      MailTemplateService templates, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.templates = templates;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Inbox receipt and the durable mail-outbox insert commit as one notification-DB transaction. */
    @Transactional
    public boolean process(Message message) {
        MessageProperties properties = message.getMessageProperties();
        JsonNode root = readBody(message);
        String eventId = firstNonBlank(properties.getMessageId(), text(root, "eventId"));
        String eventType = firstNonBlank(properties.getType(), text(root, "eventType"));
        if (eventId == null || eventId.length() > 36) {
            throw new InvalidNotificationEventException("Event id is missing or exceeds 36 characters");
        }
        if (eventType == null || eventType.length() > 120) {
            throw new InvalidNotificationEventException("Event type is missing or exceeds 120 characters");
        }
        String bodyEventId = text(root, "eventId");
        String bodyEventType = text(root, "eventType");
        if (bodyEventId != null && !eventId.equals(bodyEventId)) {
            throw new InvalidNotificationEventException("Message id does not match event id");
        }
        if (bodyEventType != null && !eventType.equals(bodyEventType)) {
            throw new InvalidNotificationEventException("Message type does not match event type");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        int inserted = jdbc.update("""
                INSERT IGNORE INTO notification_inbox(consumer,event_id,event_type,received_at)
                VALUES (?,?,?,?)
                """, CONSUMER_ID, eventId, eventType, now);
        if (inserted == 0) {
            LocalDateTime processedAt = jdbc.queryForObject("""
                    SELECT processed_at FROM notification_inbox WHERE consumer=? AND event_id=?
                    """, LocalDateTime.class, CONSUMER_ID, eventId);
            if (processedAt != null) {
                return false;
            }
            throw new IllegalStateException("An existing notification inbox row is not complete");
        }

        JsonNode data = root.path("payload").isObject() ? root.path("payload") : root;
        String recipient = firstNonBlank(text(data, "recipient"), text(data, "email"));
        if (recipient == null && "payment.refunded.v1".equals(eventType)) {
            // This financial integration event is not a mail command. Booking owns the customer notification.
            jdbc.update("UPDATE notification_inbox SET processed_at=? WHERE consumer=? AND event_id=?", now, CONSUMER_ID, eventId);
            return true;
        }
        if (recipient == null) {
            throw new InvalidNotificationEventException("Notification event has no recipient");
        }
        try {
            MailAddress.validate(recipient);
        } catch (IllegalArgumentException exception) {
            throw new InvalidNotificationEventException("Notification recipient is invalid", exception);
        }

        MailPayload mail;
        if ("notification.mail.v1".equals(eventType)) {
            mail = directMail(eventType, data);
        } else {
            String templateCode = resolveTemplateCode(eventType);
            if (templateCode == null) {
                throw new InvalidNotificationEventException("No mail template is mapped to this event type");
            }
            Map<String, Object> variables = objectMapper.convertValue(data,
                    objectMapper.getTypeFactory().constructMapType(HashMap.class, String.class, Object.class));
            variables.put("eventId", eventId);
            variables.put("eventType", eventType);
            variables.put("timestamp", now.toString());
            MailTemplateService.MailTemplate template = templates.getTemplate(templateCode)
                    .orElseThrow(() -> new IllegalStateException("Required notification template is unavailable"));
            try {
                mail = template.render(variables);
            } catch (IllegalArgumentException invalidTemplateData) {
                throw new InvalidNotificationEventException("Notification template data is invalid", invalidTemplateData);
            }
        }

        String sourceService = firstNonBlank(header(properties, "source-service"), text(root, "producer"), "unknown");
        if (sourceService.length() > 40) {
            throw new InvalidNotificationEventException("Source service exceeds 40 characters");
        }
        Integer bookingId = number(data, "bookingId");
        Integer accountId = number(data, "accountId");
        String eventKey = firstNonBlank(text(data, "eventKey"), eventId + ":" + eventType);
        if (eventKey.length() > 191) {
            throw new InvalidNotificationEventException("Notification event key exceeds 191 characters");
        }
        try {
            String payload = objectMapper.writeValueAsString(mail);
            String kind = text(data, "mailEventType");
            String mailType = "notification.mail.v1".equals(eventType)
                    && ("PAYMENT_SUCCEEDED".equals(kind) || "BOOKING_EXPIRED".equals(kind)) ? kind : eventType;
            outbox.insert(eventKey, mailType, sourceService, bookingId, accountId,
                    recipient, payload, now, now);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not encode notification mail payload", exception);
        }

        int updated = jdbc.update("""
                UPDATE notification_inbox SET processed_at=?
                WHERE consumer=? AND event_id=? AND processed_at IS NULL
                """, now, CONSUMER_ID, eventId);
        if (updated != 1) {
            throw new IllegalStateException("Could not complete the notification inbox receipt");
        }
        return true;
    }

    /** Producer-rendered mail keeps reporting/notification independent of producer templates. */
    private MailPayload directMail(String eventType, JsonNode data) {
        String subject = text(data, "subject");
        String html = text(data, "html");
        String text = text(data, "text");
        if (subject == null || subject.isBlank() || subject.length() > 200
                || subject.contains("\r") || subject.contains("\n")) {
            throw new InvalidNotificationEventException("Notification mail subject is invalid");
        }
        if (html == null || html.isBlank()) {
            throw new InvalidNotificationEventException("Notification mail html body is missing");
        }
        try {
            return new MailPayload(1, subject, html, text);
        } catch (IllegalArgumentException invalidMail) {
            throw new InvalidNotificationEventException("Notification mail payload is invalid for " + eventType,
                    invalidMail);
        }
    }

    private JsonNode readBody(Message message) {
        try {
            JsonNode root = objectMapper.readTree(message.getBody());
            if (root == null || !root.isObject()) {
                throw new InvalidNotificationEventException("Event body must be a JSON object");
            }
            return root;
        } catch (IOException exception) {
            throw new InvalidNotificationEventException("Event body is not valid JSON", exception);
        }
    }

    private String resolveTemplateCode(String eventType) {
        return switch (eventType) {
            case "booking.created.v1" -> "BOOKING_CREATED";
            case "booking.confirmed.v1" -> "BOOKING_CONFIRMED";
            case "booking.cancelled.v1" -> "BOOKING_CANCELLED";
            case "booking.expired.v1" -> "BOOKING_EXPIRED";
            case "booking.rescheduled.v1", "booking.change.approved.v1" -> "BOOKING_RESCHEDULED";
            case "booking.change.requested.v1" -> "BOOKING_CHANGE_REQUESTED";
            case "booking.mail.requested.v1" -> "BOOKING_MAIL_REQUESTED";
            case "payment.settled.v1" -> "PAYMENT_CONFIRMED";
            case "payment.refunded.v1" -> "PAYMENT_REFUNDED";
            case "tour.departure.created.v1" -> "TOUR_DEPARTURE_CREATED";
            case "tour.departure.updated.v1" -> "TOUR_DEPARTURE_UPDATED";
            case "notification.mail.v1" -> "CUSTOM_NOTIFICATION";
            default -> null;
        };
    }

    private Integer number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.canConvertToInt()) {
            throw new InvalidNotificationEventException("Notification field " + field + " is invalid");
        }
        return value.intValue();
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isValueNode() ? null : value.asText();
    }

    private String header(MessageProperties properties, String name) {
        Object value = properties.getHeaders().get(name);
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
