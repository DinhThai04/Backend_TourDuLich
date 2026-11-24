package com.example.reporting_service.consumer;

import com.example.reporting_service.config.RabbitMQConfig;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReportingEventConsumer {

    private static final String RETRY_QUEUE = "reporting.events.retry";
    private static final String DEAD_LETTER_QUEUE = "reporting.events.dlq";
    private static final String ATTEMPT_HEADER = "x-reporting-attempt";
    private static final String ERROR_HEADER = "x-reporting-error";
    private static final long[] RETRY_DELAYS_MS = {1_000, 5_000, 15_000, 60_000};

    private final ReportingEventProcessor processor;
    private final RabbitTemplate rabbitTemplate;

    @Value("${app.reporting.consumer.max-attempts:5}")
    private int maxAttempts;

    @RabbitListener(queues = RabbitMQConfig.REPORTING_EVENTS_QUEUE, ackMode = "MANUAL")
    public void consume(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String eventId = message.getMessageProperties().getMessageId();
        String eventType = message.getMessageProperties().getType();
        try {
            boolean processed = processor.process(message);
            channel.basicAck(deliveryTag, false);
            if (!processed) {
                log.debug("Acknowledged duplicate reporting event id={} type={}", eventId, eventType);
            }
        } catch (IllegalArgumentException invalidEvent) {
            moveToDeadLetter(message, channel, deliveryTag, invalidEvent.getClass().getSimpleName());
        } catch (Exception processingFailure) {
            retryOrDeadLetter(message, channel, deliveryTag, processingFailure);
        }
    }

    private void retryOrDeadLetter(Message message, Channel channel, long deliveryTag, Exception failure)
            throws IOException {
        int previousAttempt = readAttempt(message.getMessageProperties());
        if (previousAttempt + 1 >= maxAttempts) {
            moveToDeadLetter(message, channel, deliveryTag, failure.getClass().getSimpleName());
            return;
        }

        int nextAttempt = previousAttempt + 1;
        long delay = RETRY_DELAYS_MS[Math.min(previousAttempt, RETRY_DELAYS_MS.length - 1)];
        try {
            publish(RETRY_QUEUE, copyForRetry(message, nextAttempt, delay,
                    failure.getClass().getSimpleName()));
            channel.basicAck(deliveryTag, false);
            log.warn("Reporting event scheduled for retry id={} type={} attempt={}",
                    message.getMessageProperties().getMessageId(),
                    message.getMessageProperties().getType(), nextAttempt);
        } catch (Exception publishFailure) {
            log.error("Could not safely schedule reporting retry id={} type={}",
                    message.getMessageProperties().getMessageId(), message.getMessageProperties().getType());
            channel.basicNack(deliveryTag, false, true);
        }
    }

    private void moveToDeadLetter(Message message, Channel channel, long deliveryTag, String reason)
            throws IOException {
        try {
            Message deadLetter = copyForRetry(message, readAttempt(message.getMessageProperties()), 0, reason);
            publish(DEAD_LETTER_QUEUE, deadLetter);
            channel.basicAck(deliveryTag, false);
            log.error("Reporting event moved to dead-letter queue id={} type={} reason={}",
                    message.getMessageProperties().getMessageId(), message.getMessageProperties().getType(), reason);
        } catch (Exception publishFailure) {
            log.error("Could not safely dead-letter reporting event id={} type={}",
                    message.getMessageProperties().getMessageId(), message.getMessageProperties().getType());
            channel.basicNack(deliveryTag, false, true);
        }
    }

    private void publish(String queue, Message message) throws Exception {
        CorrelationData confirmation = new CorrelationData(
                message.getMessageProperties().getMessageId() + ":" + queue + ":" + System.nanoTime());
        rabbitTemplate.send("", queue, message, confirmation);
        CorrelationData.Confirm result = confirmation.getFuture().get(5, TimeUnit.SECONDS);
        if (!result.isAck() || confirmation.getReturned() != null) {
            throw new IllegalStateException("RabbitMQ did not confirm the reporting retry/dead-letter publish");
        }
    }

    private Message copyForRetry(Message original, int attempt, long delayMs, String reason) {
        MessageProperties source = original.getMessageProperties();
        MessageProperties target = new MessageProperties();
        target.setContentType(source.getContentType());
        target.setContentEncoding(source.getContentEncoding());
        target.setMessageId(source.getMessageId());
        target.setType(source.getType());
        target.setCorrelationId(source.getCorrelationId());
        target.setTimestamp(source.getTimestamp());
        target.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        target.getHeaders().putAll(source.getHeaders());
        target.setHeader(ATTEMPT_HEADER, attempt);
        target.setHeader(ERROR_HEADER, reason);
        if (delayMs > 0) {
            target.setExpiration(Long.toString(delayMs));
        }
        return new Message(original.getBody(), target);
    }

    private int readAttempt(MessageProperties properties) {
        Object value = properties.getHeaders().get(ATTEMPT_HEADER);
        if (value instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (value instanceof String text) {
            try {
                return Math.max(0, Integer.parseInt(text));
            } catch (NumberFormatException ignored) {
                return maxAttempts;
            }
        }
        return 0;
    }
}
