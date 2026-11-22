package com.example.payment_service.notification;

import com.example.payment_service.repository.httpclient.AuthInternalClient;
import com.example.payment_service.repository.httpclient.TourInternalClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** Same datasource/transaction as VNPay settlement. No HTTP or SMTP inside this writer. */
@Component
@Slf4j
public class PaymentMailOutboxWriter {
	private final JdbcTemplate jdbc;
	private final ObjectMapper mapper;
	private final AuthInternalClient authClient;
	private final TourInternalClient tourClient;
	
	public PaymentMailOutboxWriter(JdbcTemplate jdbc, ObjectMapper mapper, AuthInternalClient authClient, TourInternalClient tourClient) { 
		this.jdbc = jdbc; 
		this.mapper = mapper; 
		this.authClient = authClient;
		this.tourClient = tourClient;
	}
	
	public void receipt(int bookingId, String ref, long amount, LocalDateTime paidAt, LocalDateTime now, String provider) {
		write(bookingId, "PAYMENT_SUCCEEDED", "booking:" + bookingId + ":paid:" + provider + ":" + ref, ref, amount, paidAt, now);
	}
	public void expired(int bookingId, LocalDateTime now) {
		write(bookingId, "BOOKING_EXPIRED", "booking:" + bookingId + ":booking_expired", null, null, null, now);
	}
	private void write(int bookingId, String type, String key, String ref, Long amount, LocalDateTime paidAt, LocalDateTime now) {
		// Get booking basic info
		var booking = jdbc.queryForMap("SELECT account_id, tour_departure_id FROM booking WHERE id=?", bookingId);
		if (booking == null || booking.isEmpty()) {
			log.warn("Skipping {} mail for booking {}: booking not found", type, bookingId);
			return;
		}
		
		int accountId = ((Number) booking.get("account_id")).intValue();
		int departureId = ((Number) booking.get("tour_departure_id")).intValue();
		
		// Get user info via Auth internal API
		String email, username;
		try {
			Map<String, Object> user = authClient.getUserById((long) accountId);
			email = (String) user.get("email");
			username = (String) user.get("username");
		} catch (Exception e) {
			log.warn("Skipping {} mail for booking {}: cannot fetch user info", type, bookingId);
			return;
		}
		
		// Get tour info via Tour internal API
		String tourTitle;
		LocalDateTime departureDate;
		try {
			Map<String, Object> departure = tourClient.getDeparture(departureId);
			tourTitle = (String) departure.get("tour_id") != null ? "Tour #" + departure.get("tour_id") : "Tour";
			Object depDate = departure.get("departure_date");
			departureDate = depDate instanceof LocalDateTime ? (LocalDateTime) depDate : ((java.sql.Timestamp) depDate).toLocalDateTime();
		} catch (Exception e) {
			log.warn("Skipping {} mail for booking {}: cannot fetch tour info", type, bookingId);
			return;
		}
		
		// Get passenger count
		int passengers = jdbc.queryForObject("SELECT COUNT(*) FROM customer WHERE booking_id=?", Integer.class, bookingId);
		
		var payload = new LinkedHashMap<String,Object>();
		payload.put("version", 1); payload.put("customerName", username); payload.put("tourTitle", tourTitle);
		payload.put("departureId", departureId); payload.put("departureAt", departureDate);
		payload.put("passengerCount", passengers); payload.put("amountVnd", amount);
		payload.put("transactionRef", ref); payload.put("paidAt", paidAt);
		try {
			jdbc.update("""
				INSERT INTO mail_outbox (event_key,event_type,source_service,booking_id,account_id,recipient,payload,
					scheduled_at,status,next_attempt_at,created_at,updated_at)
				VALUES (?,?,'payment_service',?,?,?,?,?,'PENDING',?,?,?)
				""", key, type, bookingId, accountId, email, mapper.writeValueAsString(payload), now, now, now, now);
		} catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("Cannot encode payment mail", e); }
		// Do not suppress SQL errors: roll back settlement instead of losing the event.
	}
	private static LocalDateTime date(Object value) {
		return value instanceof LocalDateTime date ? date : ((java.sql.Timestamp)value).toLocalDateTime();
	}
}