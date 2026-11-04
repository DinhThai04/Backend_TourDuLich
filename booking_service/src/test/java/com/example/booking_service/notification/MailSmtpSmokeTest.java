package com.example.booking_service.notification;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import java.time.LocalDateTime;

/** Explicit opt-in; sends ONE real test email only to the user's authorised address. */
@EnabledIfEnvironmentVariable(named = "MAIL_SMTP_SMOKE_TEST", matches = "true")
class MailSmtpSmokeTest {
    @Test void sendOneAuthorisedTestEmail() throws Exception {
        String password = System.getenv("MAIL_SMTP_PASSWORD");
        if (password == null || password.isBlank()) throw new IllegalStateException("Set MAIL_SMTP_PASSWORD locally before smoke test");
        var properties = new MailProperties(); properties.setEnabled(true);
        // The sender mailbox is the operator's own authorised address; nothing personal is hardcoded.
        String mailbox = System.getenv("MAIL_SMOKE_ADDRESS");
        if (mailbox == null || mailbox.isBlank()) throw new IllegalStateException("Set MAIL_SMOKE_ADDRESS to the authorised mailbox before smoke test");
        properties.setFrom(mailbox); properties.setTestRecipient(mailbox);
        var smtp = new JavaMailSenderImpl(); smtp.setHost("smtp.gmail.com"); smtp.setPort(587);
        smtp.setUsername(properties.getFrom()); smtp.setPassword(password); smtp.setDefaultEncoding("UTF-8");
        var javaMail = smtp.getJavaMailProperties();
        javaMail.setProperty("mail.smtp.auth", "true");
        javaMail.setProperty("mail.smtp.starttls.enable", "true");
        javaMail.setProperty("mail.smtp.starttls.required", "true");
        javaMail.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        javaMail.setProperty("mail.smtp.connectiontimeout", "5000");
        javaMail.setProperty("mail.smtp.timeout", "10000");
        javaMail.setProperty("mail.smtp.writetimeout", "10000");
        var renderer = new MailTemplateRenderer(MailTemplateRendererTest.engine(), JsonMapper.builder().findAndAddModules().build(), properties,
                org.mockito.Mockito.mock(MailTourImageResolver.class));
        var event = new MailOutboxItem(0, MailEventType.TEST_MAIL, null, null, properties.getTestRecipient(), "{}", 0, "smoke");
        new SpringMailSender(smtp, properties).send(properties.getTestRecipient(), renderer.render(event, MailPayload.test()));
    }
}
