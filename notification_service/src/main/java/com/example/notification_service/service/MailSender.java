package com.example.notification_service.service;

import com.example.notification_service.domain.MailPayload;

public interface MailSender {
    void send(String recipient, MailPayload payload) throws Exception;
}
