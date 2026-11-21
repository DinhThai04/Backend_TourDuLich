package com.example.notification_service.service;

import com.example.notification_service.config.MailAddress;
import com.example.notification_service.config.MailProperties;
import com.example.notification_service.domain.MailPayload;
import jakarta.mail.MessagingException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.mail.enabled", havingValue = "true")
public class SmtpMailSender implements MailSender {

    private final JavaMailSender sender;
    private final MailProperties properties;

    public SmtpMailSender(JavaMailSender sender, MailProperties properties) {
        this.sender = sender;
        this.properties = properties;
        properties.validate();
    }

    @Override
    public void send(String recipient, MailPayload payload) throws MessagingException {
        MailAddress.validate(recipient);
        if (properties.isTestMode() && !recipient.equalsIgnoreCase(properties.getTestRecipient()))
            throw new IllegalArgumentException("Recipient is not the configured test mailbox");
        var message = sender.createMimeMessage();
        var helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(properties.getFrom());
        helper.setReplyTo(properties.getFrom());
        helper.setTo(recipient);
        helper.setSubject(payload.subject());
        helper.setText(payload.text(), payload.html());
        sender.send(message);
    }
}
