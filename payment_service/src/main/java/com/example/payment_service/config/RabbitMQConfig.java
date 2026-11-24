package com.example.payment_service.config;

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
    public static final String PAYMENT_SETTLED_QUEUE = "payment.settled.events";
    public static final String PAYMENT_REFUNDED_QUEUE = "payment.refunded.events";
    public static final String PAYMENT_DLQ = "payment.events.dlq";

    @Bean
    DirectExchange eventExchange() {
        return new DirectExchange(EVENT_EXCHANGE, true, false);
    }

    @Bean
    Queue paymentSettledQueue() {
        return QueueBuilder.durable(PAYMENT_SETTLED_QUEUE)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", PAYMENT_DLQ)
                .build();
    }

    @Bean
    Queue paymentRefundedQueue() {
        return QueueBuilder.durable(PAYMENT_REFUNDED_QUEUE)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", PAYMENT_DLQ)
                .build();
    }

    @Bean
    Queue paymentDLQ() {
        return QueueBuilder.durable(PAYMENT_DLQ).build();
    }

    @Bean
    Binding paymentSettledBinding() {
        return BindingBuilder.bind(paymentSettledQueue())
                .to(eventExchange())
                .with("payment.settled.v1");
    }

    @Bean
    Binding paymentRefundedBinding() {
        return BindingBuilder.bind(paymentRefundedQueue())
                .to(eventExchange())
                .with("payment.refunded.v1");
    }
}