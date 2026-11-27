package com.example.booking_service.notification;

import com.example.booking_service.config.BookingMailOutboxPublisher;
import com.example.booking_service.config.NotificationMailClient;
import com.example.booking_service.repository.httpclient.AuthInternalClient;
import com.example.booking_service.repository.httpclient.InvoiceInternalClient;
import com.example.booking_service.repository.httpclient.PaymentInternalClient;
import com.example.booking_service.repository.httpclient.TourInternalClient;
import com.example.booking_service.reservation.LifecycleTestDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReminderPlannerTest {
    @Test void acceptsIsoDepartureAndPublishesCompleteReminderExactlyOncePerMilestone() throws Exception {
        var db = new LifecycleTestDatabase();
        var now = LocalDateTime.of(2026, 11, 15, 9, 0);
        var departure = LocalDateTime.of(2026, 11, 20, 6, 0);
        db.jdbc.update("INSERT INTO booking(id,account_id,tour_departure_id,status,created_at) VALUES (1,7,1,'CONFIRMED',?)",
                now.minusDays(3));
        db.jdbc.update("INSERT INTO customer(booking_id,full_name) VALUES (1,'Nguyễn Văn An')");
        var auth = mock(AuthInternalClient.class);
        when(auth.getUserById(7L)).thenReturn(Map.of("email", "tester@example.com", "username", "An"));
        var tour = mock(TourInternalClient.class);
        when(tour.getDeparture(1)).thenReturn(Map.of("departure_date", departure.toString(), "tour_title", "Tour miền Bắc"));
        var payment = mock(PaymentInternalClient.class);
        when(payment.getBookingPaymentEvidence(1)).thenReturn(Map.of("paidPayment", true));
        when(payment.getPaymentStatus(1)).thenReturn(Map.of("status", "PAID",
                "dayOfPay", now.minusDays(2).toString(), "totalBookingTourExpense", 1000));
        var invoice = mock(InvoiceInternalClient.class);
        when(invoice.getInvoiceByBookingId(1)).thenReturn(Map.of("status", "PAID",
                "dayOfPay", now.minusDays(2).toString(), "totalBookingTourExpense", 1000));
        var notification = mock(NotificationMailClient.class);
        var publisher = new BookingMailOutboxPublisher(db.jdbc, notification, "1970-01-01T00:00:00");
        var properties = new MailProperties();
        properties.setTestRecipient("tester@example.com");
        var json = new ObjectMapper();
        var planner = new ReminderPlanner(db.jdbc, publisher, properties, new LifecycleTestDatabase.MutableClock(now),
                json, auth, tour, payment, invoice);

        assertEquals(1, planner.dispatch(ReminderPlanner.Request.preview()).eligible());
        assertEquals(0, db.count("booking_outbox"));
        var send = new ReminderPlanner.Request(false, 50, 0);
        assertEquals(1, planner.dispatch(send).created());
        assertEquals(1, planner.dispatch(send).alreadyExists());
        assertEquals(1, db.count("booking_outbox"));
        publisher.publishPendingEvents();
        publisher.publishPendingEvents();
        var payload = ArgumentCaptor.forClass(String.class);
        verify(notification).sendBookingMail(payload.capture());
        var sent = json.readTree(payload.getValue());
        assertEquals("tester@example.com", sent.path("recipient").asText());
        assertEquals("DEPARTURE_REMINDER", sent.path("eventType").asText());
        assertEquals(1, sent.path("bookingId").asInt());
        assertEquals("PUBLISHED", db.jdbc.queryForObject("SELECT status FROM booking_outbox", String.class));
    }

    @Test void dispatchStartLeavesOlderMailInQueueWithoutReleasingIt() throws Exception {
        var db = new LifecycleTestDatabase();
        var notification = mock(NotificationMailClient.class);
        var publisher = new BookingMailOutboxPublisher(db.jdbc, notification, "2026-11-27T00:00:00");
        String oldMail = "{\"bookingId\":99}";
        String newMail = "{\"bookingId\":100}";
        publisher.sendNow(oldMail);
        publisher.sendNow(newMail);
        db.jdbc.update("UPDATE booking_outbox SET created_at='2026-11-26 23:59:59.123456' WHERE aggregate_id=99");
        db.jdbc.update("UPDATE booking_outbox SET created_at='2026-11-27 00:00:00.123456' WHERE aggregate_id=100");

        publisher.publishPendingEvents();

        verify(notification).sendBookingMail(newMail);
        verify(notification, never()).sendBookingMail(oldMail);
        assertEquals("PENDING", db.jdbc.queryForObject("SELECT status FROM booking_outbox WHERE aggregate_id=99", String.class));
        assertEquals("PUBLISHED", db.jdbc.queryForObject("SELECT status FROM booking_outbox WHERE aggregate_id=100", String.class));
    }
}
