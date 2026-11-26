package com.example.booking_service.reservation;

import com.example.booking_service.config.RabbitMQConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BookingSettlementListener {
    private final ObjectMapper mapper;
    private final BookingSettlementService settlements;

    @RabbitListener(queues = RabbitMQConfig.BOOKING_PAYMENT_QUEUE)
    public void settled(byte[] payload) throws java.io.IOException {
        int bookingId = mapper.readTree(payload).path("bookingId").asInt();
        if (bookingId <= 0) throw new AmqpRejectAndDontRequeueException("Thiếu mã đơn trong sự kiện thanh toán");
        settlements.confirmPaid(bookingId);
    }
}
