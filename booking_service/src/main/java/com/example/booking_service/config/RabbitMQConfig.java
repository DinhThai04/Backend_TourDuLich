package com.example.booking_service.config;

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
    public static final String BOOKING_HOLD_QUEUE = "booking.hold.events";
    public static final String BOOKING_RESCHEDULE_QUEUE = "booking.reschedule.events";
    public static final String BOOKING_DLQ = "booking.events.dlq";

    @Bean
    DirectExchange eventExchange() {
        return new DirectExchange(EVENT_EXCHANGE, true, false);
    }

    @Bean
    Queue bookingHoldQueue() {
        return QueueBuilder.durable(BOOKING_HOLD_QUEUE)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", BOOKING_DLQ)
                .build();
    }

    @Bean
    Queue bookingRescheduleQueue() {
        return QueueBuilder.durable(BOOKING_RESCHEDULE_QUEUE)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", BOOKING_DLQ)
                .build();
    }

    @Bean
    Queue bookingDLQ() {
        return QueueBuilder.durable(BOOKING_DLQ).build();
    }

    @Bean
    Binding bookingHoldCreatedBinding() {
        return BindingBuilder.bind(bookingHoldQueue())
                .to(eventExchange())
                .with("booking.hold.created.v1");
    }

    @Bean
    Binding bookingHoldExpiredBinding() {
        return BindingBuilder.bind(bookingHoldQueue())
                .to(eventExchange())
                .with("booking.hold.expired.v1");
    }

    @Bean
    Binding bookingRescheduleBinding() {
        return BindingBuilder.bind(bookingRescheduleQueue())
                .to(eventExchange())
                .with("booking.reschedule.v1");
    }

    @Bean
    Binding bookingRefundBinding() {
        return BindingBuilder.bind(bookingRescheduleQueue())
                .to(eventExchange())
                .with("booking.refund.v1");
    }
}