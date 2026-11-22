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
    Binding notificationMailBinding() {
        return BindingBuilder.bind(notificationMailQueue())
                .to(eventExchange())
                .with("notification.mail.v1");
    }
}