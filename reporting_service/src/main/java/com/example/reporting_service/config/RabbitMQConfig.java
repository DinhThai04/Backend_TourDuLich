package com.example.reporting_service.config;

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
    public static final String REPORTING_EVENTS_QUEUE = "reporting.events";
    public static final String REPORTING_RETRY_QUEUE = "reporting.events.retry";
    public static final String REPORTING_DLQ = "reporting.events.dlq";

    @Bean
    DirectExchange eventExchange() {
        return new DirectExchange(EVENT_EXCHANGE, true, false);
    }

    @Bean
    Queue reportingEventsQueue() {
        return QueueBuilder.durable(REPORTING_EVENTS_QUEUE)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", REPORTING_DLQ)
                .build();
    }

    @Bean
    Queue reportingDLQ() {
        return QueueBuilder.durable(REPORTING_DLQ).build();
    }

    @Bean
    Queue reportingRetryQueue() {
        return QueueBuilder.durable(REPORTING_RETRY_QUEUE)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", REPORTING_EVENTS_QUEUE)
                .build();
    }

    @Bean
    Binding bookingCreatedBinding() {
        return BindingBuilder.bind(reportingEventsQueue())
                .to(eventExchange())
                .with("booking.created.v1");
    }

    @Bean
    Binding bookingConfirmedBinding() {
        return BindingBuilder.bind(reportingEventsQueue())
                .to(eventExchange())
                .with("booking.confirmed.v1");
    }

    @Bean
    Binding bookingCancelledBinding() {
        return BindingBuilder.bind(reportingEventsQueue())
                .to(eventExchange())
                .with("booking.cancelled.v1");
    }

    @Bean
    Binding bookingExpiredBinding() {
        return BindingBuilder.bind(reportingEventsQueue())
                .to(eventExchange())
                .with("booking.expired.v1");
    }

    @Bean
    Binding paymentSettledBinding() {
        return BindingBuilder.bind(reportingEventsQueue())
                .to(eventExchange())
                .with("payment.settled.v1");
    }

    @Bean
    Binding paymentRefundedBinding() {
        return BindingBuilder.bind(reportingEventsQueue())
                .to(eventExchange())
                .with("payment.refunded.v1");
    }

    @Bean
    Binding tourDepartureCreatedBinding() {
        return BindingBuilder.bind(reportingEventsQueue())
                .to(eventExchange())
                .with("tour.departure.created.v1");
    }

    @Bean
    Binding tourDepartureUpdatedBinding() {
        return BindingBuilder.bind(reportingEventsQueue())
                .to(eventExchange())
                .with("tour.departure.updated.v1");
    }
}
