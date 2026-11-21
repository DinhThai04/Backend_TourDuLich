package com.example.notification_service.service;

import com.example.notification_service.config.MailAddress;
import com.example.notification_service.domain.MailPayload;
import com.example.notification_service.dto.MailEnqueueRequest;
import com.example.notification_service.repository.MailOutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

@Service
public class MailEnqueueService {

    private final MailOutboxRepository repository;
    private final ObjectMapper mapper;
    private final Clock clock;

    public MailEnqueueService(MailOutboxRepository repository, ObjectMapper mapper, Clock clock) {
        this.repository = repository;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public boolean enqueue(MailEnqueueRequest request) {
        MailAddress.validate(request.recipient());
        MailPayload payload = new MailPayload(1, request.subject(), request.html(), request.text());
        try {
            return repository.insert(request.eventKey(), request.eventType(), request.sourceService(),
                    request.bookingId(), request.accountId(), request.recipient(),
                    mapper.writeValueAsString(payload), request.scheduledAt(), LocalDateTime.now(clock));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Cannot encode mail payload", exception);
        }
    }
}
