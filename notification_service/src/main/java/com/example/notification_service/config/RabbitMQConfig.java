package com.example.notification_service.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String EVENT_EXCHANGE = "sgu.events";
    public static final String NOTIFICATION_MAIL_QUEUE = "notification.mail.events";
    public static final String NOTIFICATION_RETRY_QUEUE = "notification.mail.events.retry";
    public static final String NOTIFICATION_DLQ = "notification.events.dlq";

    @Bean
    DirectExchange eventExchange() {
        return new DirectExchange(EVENT_EXCHANGE, true, false);
    }

    @Bean
    Queue notificationMailQueue() {
        return QueueBuilder.durable(NOTIFICATION_MAIL_QUEUE)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", NOTIFICATION_DLQ)
                .build();
    }

    @Bean
    Queue notificationDLQ() {
        return QueueBuilder.durable(NOTIFICATION_DLQ).build();
    }

    @Bean
    Queue notificationRetryQueue() {
        return QueueBuilder.durable(NOTIFICATION_RETRY_QUEUE)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", NOTIFICATION_MAIL_QUEUE)
                .build();
    }

    @Bean
    Binding notificationMailBinding() {
        return BindingBuilder.bind(notificationMailQueue())
                .to(eventExchange())
                .with("notification.mail.v1");
    }

    @Bean
    Binding bookingCreatedBinding() {
        return bind("booking.created.v1");
    }

    @Bean
    Binding bookingConfirmedBinding() {
        return bind("booking.confirmed.v1");
    }

    @Bean
    Binding bookingCancelledBinding() {
        return bind("booking.cancelled.v1");
    }

    @Bean
    Binding bookingExpiredBinding() {
        return bind("booking.expired.v1");
    }

    @Bean
    Binding bookingChangeRequestedBinding() {
        return bind("booking.change.requested.v1");
    }

    @Bean
    Binding bookingChangeApprovedBinding() {
        return bind("booking.change.approved.v1");
    }

    @Bean
    Binding bookingMailRequestedBinding() {
        return bind("booking.mail.requested.v1");
    }

    @Bean
    Binding paymentRefundedBinding() {
        return bind("payment.refunded.v1");
    }

    @Bean
    Binding tourDepartureCreatedBinding() {
        return bind("tour.departure.created.v1");
    }

    @Bean
    Binding tourDepartureUpdatedBinding() {
        return bind("tour.departure.updated.v1");
    }

    private Binding bind(String routingKey) {
        return BindingBuilder.bind(notificationMailQueue()).to(eventExchange()).with(routingKey);
    }
}
