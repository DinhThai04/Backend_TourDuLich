package com.example.booking_service.notification;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

class MailConfigurationTest {
    @Test void disabledByDefaultDoesNotRequireSmtpOrOutboxTable() {
        new ApplicationContextRunner().withUserConfiguration(MailConfiguration.class,
                SpringMailSender.class, MailDeliveryWorker.class).run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBeansOfType(SpringMailSender.class).isEmpty());
            assertTrue(context.getBeansOfType(MailDeliveryWorker.class).isEmpty());
            assertFalse(context.getBean(MailProperties.class).isEnabled());
        });
    }
    @Test void defaultMailboxIsAPlaceholderNotAPersonalAddress() {
        new ApplicationContextRunner().withUserConfiguration(MailConfiguration.class).run(context -> {
            var properties = context.getBean(MailProperties.class);
            assertTrue(properties.isTestMode());
            assertEquals("no-reply@sgu-tour.example", properties.getTestRecipient());
            assertEquals("no-reply@sgu-tour.example", properties.getFrom());
            properties.validate();
        });
    }
}
