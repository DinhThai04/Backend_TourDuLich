package com.example.booking_service.notification;

import com.example.booking_service.config.BookingMailOutboxPublisher;
import com.example.booking_service.repository.httpclient.InvoiceInternalClient;
import com.example.booking_service.repository.httpclient.PaymentInternalClient;
import com.example.booking_service.repository.httpclient.TourInternalClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
	private final TransactionTemplate transactions;
	private final ReminderStateReader reader;
	private final ReminderRules rules = new ReminderRules();
	private final TourInternalClient tourClient;
	
	public ReminderPlanner(JdbcTemplate jdbc, BookingMailOutboxPublisher publisher, MailProperties properties, Clock clock,
			PlatformTransactionManager manager, TourInternalClient tourClient,
			PaymentInternalClient paymentClient, InvoiceInternalClient invoiceClient) {
		this.jdbc=jdbc; this.publisher=publisher; this.properties=properties; this.clock=clock;
		this.transactions=new TransactionTemplate(manager);
		this.reader=new ReminderStateReader(jdbc, null, tourClient, paymentClient, invoiceClient);
		this.tourClient = tourClient;
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
				// Check departure date via Tour internal API
				var booking = jdbc.queryForMap("SELECT tour_departure_id FROM booking WHERE id=?", id);
				Integer departureId = (Integer) booking.get("tour_departure_id");
				if (departureId == null) continue;
				LocalDateTime departureDate;
				try {
					Map<String, Object> departure = tourClient.getDeparture(departureId);
					Object depDate = departure.get("departure_date");
					departureDate = depDate instanceof LocalDateTime ? (LocalDateTime) depDate : ((java.sql.Timestamp) depDate).toLocalDateTime();
					if (!departureDate.toLocalDate().isAfter(now.toLocalDate())) continue;
				} catch (Exception e) {
					continue; // Skip if tour service unavailable
				}
				
				String outcome=transactions.execute(tx -> {
					var state=reader.read(id);
					if(state.isEmpty()) return "skip";
					var s=state.get();
					var selected=rules.select(s,now);
					if(selected.isEmpty()) return "skip";
					if(properties.isTestMode() && !properties.getTestRecipient().equalsIgnoreCase(s.recipient())) return "skip";
					if (!isValidRecipient(s.recipient())) { return "invalid_recipient"; }
					var choice=selected.get();
					if(request.dryRun()) return "preview";
					var payload=new MailPayload(1,s.name(),s.tour(),s.departureId(),s.departureAt(),s.passengers(),
							null,null,null,choice.deadline(),choice.daysBefore(),choice.due());
					var payloadJson = toJson(payload);
					publisher.sendNow(payloadJson);
					return "created";
				});
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

	private boolean isValidRecipient(String recipient) {
		return recipient != null && recipient.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
	}

	private String toJson(Object value) {
		try {
			return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
		} catch (com.fasterxml.jackson.core.JsonProcessingException e) {
			throw new IllegalStateException("Cannot serialize payload", e);
		}
	}
}