package com.example.invoice_service.config;

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
    public static final String INVOICE_PAYMENT_QUEUE = "invoice.payment.events";
    public static final String INVOICE_DLQ = "invoice.payment.events.dlq";

    @Bean
    DirectExchange eventExchange() {
        return new DirectExchange(EVENT_EXCHANGE, true, false);
    }

    @Bean
    Queue invoicePaymentQueue() {
        return QueueBuilder.durable(INVOICE_PAYMENT_QUEUE)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", INVOICE_DLQ)
                .build();
    }

    @Bean
    Queue invoiceDLQ() {
        return QueueBuilder.durable(INVOICE_DLQ).build();
    }

    @Bean
    Binding invoicePaymentSettledBinding() {
        return BindingBuilder.bind(invoicePaymentQueue())
                .to(eventExchange())
                .with("payment.settled.v1");
    }

    @Bean
    Binding invoicePaymentRefundedBinding() {
        return BindingBuilder.bind(invoicePaymentQueue())
                .to(eventExchange())
                .with("payment.refunded.v1");
    }
}