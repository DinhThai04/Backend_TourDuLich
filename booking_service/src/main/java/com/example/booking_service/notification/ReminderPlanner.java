package com.example.booking_service.notification;

import com.example.booking_service.config.BookingMailOutboxPublisher;
import com.example.booking_service.repository.httpclient.AuthInternalClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.booking_service.repository.httpclient.InvoiceInternalClient;
import com.example.booking_service.repository.httpclient.PaymentInternalClient;
import com.example.booking_service.repository.httpclient.TourInternalClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class ReminderPlanner {
	private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ReminderPlanner.class);
	public record Request(boolean dryRun, int batchSize, int afterBookingId) {
		public Request {
			if (batchSize < 1 || batchSize > 200 || afterBookingId < 0) throw new IllegalArgumentException("batchSize: 1..200; afterBookingId >= 0");
		}
		public static Request preview() { return new Request(true,100,0); }
	}
	public record Result(boolean dryRun, int scanned, int eligible, int created, int alreadyExists,
			int skipped, int failed, int nextCursor, boolean hasMore, int invalidRecipients) {}
	private final JdbcTemplate jdbc;
	private final BookingMailOutboxPublisher publisher;
	private final MailProperties properties;
	private final Clock clock;
	private final ObjectMapper json;
	private final ReminderStateReader reader;
	private final ReminderRules rules = new ReminderRules();
	
	public ReminderPlanner(JdbcTemplate jdbc, BookingMailOutboxPublisher publisher, MailProperties properties, Clock clock,
			ObjectMapper json, AuthInternalClient authClient, TourInternalClient tourClient,
			PaymentInternalClient paymentClient, InvoiceInternalClient invoiceClient) {
		this.jdbc=jdbc; this.publisher=publisher; this.properties=properties; this.clock=clock;
		this.json=json;
		this.reader=new ReminderStateReader(jdbc, authClient, tourClient, paymentClient, invoiceClient);
	}
	public Result dispatch(Request request) {
		LocalDateTime now=LocalDateTime.now(clock).withNano(0);
		// Keyset pagination scans every eligible booking, not only the first batch every hour.
		var ids=jdbc.queryForList("""
			SELECT b.id FROM booking b 
			WHERE b.id>? AND b.status IN ('PENDING_PAYMENT','CONFIRMED') AND b.tour_departure_id IS NOT NULL ORDER BY b.id LIMIT ?
			""",Integer.class,request.afterBookingId(),request.batchSize()+1);
		boolean more=ids.size()>request.batchSize();
		int count=Math.min(ids.size(),request.batchSize()),eligible=0,created=0,exists=0,skipped=0,failed=0,invalidRecipients=0,cursor=request.afterBookingId();
		for(int index=0;index<count;index++) {
			int id=ids.get(index); cursor=id;
			try {
				String outcome = planReminder(id, now, request.dryRun());
				if("invalid_recipient".equals(outcome)) {
					skipped++;invalidRecipients++;
					log.warn("Reminder skipped for booking {} (invalid recipient)",id);
				}
				else if("skip".equals(outcome)) skipped++;
				else { eligible++; if("created".equals(outcome)) created++; else if("exists".equals(outcome)) exists++; }
			} catch(Exception error) {
				if(error instanceof org.springframework.dao.DataAccessException databaseFailure) throw databaseFailure;
				log.warn("Reminder planning failed for booking {} ({})",id,error.getClass().getSimpleName());
				failed++;
			}
		}
		return new Result(request.dryRun(),count,eligible,created,exists,skipped,failed,cursor,more,invalidRecipients);
	}

    private String planReminder(int id, LocalDateTime now, boolean dryRun) throws JsonProcessingException {
        var state = reader.read(id);
        if (state.isEmpty()) return "skip";
        var booking = state.get();
        var selected = rules.select(booking, now);
        if (selected.isEmpty()) return "skip";
        String recipient = booking.recipient();
        if (recipient == null || recipient.length() > 254 || !recipient.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))
            return "invalid_recipient";
        if (properties.isTestMode() && !recipient.equalsIgnoreCase(properties.getTestRecipient())) return "skip";
        if (dryRun) return "preview";

        var choice = selected.get();
        boolean paymentReminder = choice.type() == MailEventType.PAYMENT_REMINDER;
        String title = paymentReminder ? "Nhắc thanh toán" : "Nhắc khởi hành";
        var format = java.time.format.DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");
        String details = paymentReminder ? "Hạn thanh toán: " + format.format(choice.deadline())
                : "Giờ khởi hành: " + format.format(booking.departureAt());
        String text = "Đơn #" + id + " · " + booking.tour() + ". " + details + " (giờ Việt Nam). "
                + "Mở lịch sử đặt tour trên SGU Tour để kiểm tra thông tin mới nhất.";
        String key = rules.key(booking, choice);
        var payload = Map.of(
                "eventKey", key, "eventType", choice.type().name(), "sourceService", "booking-service",
                "bookingId", id, "accountId", booking.accountId(), "recipient", recipient,
                "subject", "SGU Tour · " + title + " (#" + id + ")",
                "html", "<p>" + org.springframework.web.util.HtmlUtils.htmlEscape(text) + "</p>",
                "text", text, "scheduledAt", now.toString());
        return publisher.enqueueOnce(id, key, json.writeValueAsString(payload)) ? "created" : "exists";
    }
}
