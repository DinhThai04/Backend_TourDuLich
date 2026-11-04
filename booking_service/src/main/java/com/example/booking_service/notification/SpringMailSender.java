package com.example.booking_service.notification;

import jakarta.mail.MessagingException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.mail.enabled", havingValue = "true")
public class SpringMailSender {
    private final JavaMailSender sender;
    private final MailProperties properties;
    public SpringMailSender(JavaMailSender sender, MailProperties properties) {
        this.sender = sender; this.properties = properties; properties.validate();
    }
    public void send(String recipient, MailTemplateRenderer.RenderedMail mail) throws MessagingException {
        if (!properties.isEnabled()) throw new IllegalStateException("Mail delivery is disabled");
        MailOutboxService.validateAddress(recipient);
        // Reject rather than silently redirect real customer mail and mark it SENT.
        if (properties.isTestMode() && !recipient.equalsIgnoreCase(properties.getTestRecipient()))
            throw new IllegalArgumentException("Recipient is not the configured test mailbox");
        var message = sender.createMimeMessage();
        var helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(properties.getFrom());
        helper.setReplyTo(properties.getFrom());
        helper.setTo(recipient);
        helper.setSubject(mail.subject());
        helper.setText(mail.text(), mail.html());
        helper.addInline("sgu-tour-logo", new ClassPathResource("templates/mail/assets/logo.png"), "image/png");
        sender.send(message);
    }
}
