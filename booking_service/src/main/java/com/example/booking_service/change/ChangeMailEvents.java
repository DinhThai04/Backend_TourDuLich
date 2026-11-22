package com.example.booking_service.change;

import com.example.booking_service.notification.MailEventType;
import com.example.booking_service.notification.MailOutboxService;
import com.example.booking_service.notification.MailPayload;
import com.example.booking_service.repository.httpclient.AuthInternalClient;
import com.example.booking_service.repository.httpclient.TourInternalClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import static com.example.booking_service.change.ChangeLedger.date;
import static com.example.booking_service.change.ChangeLedger.n;
import static com.example.booking_service.change.ChangeLedger.v;

@Component
public class ChangeMailEvents {

	private static final String EVENT_SOURCE = "booking_service";
	private static final DateTimeFormatter DEADLINE_FORMAT = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");

	private final JdbcTemplate jdbc;
	private final MailOutboxService outbox;
	private final Clock clock;
	private final AuthInternalClient authClient;
	private final TourInternalClient tourClient;

	public ChangeMailEvents(JdbcTemplate jdbc, MailOutboxService outbox, Clock clock, AuthInternalClient authClient, TourInternalClient tourClient) {
		this.jdbc = jdbc;
		this.outbox = outbox;
		this.clock = clock;
		this.authClient = authClient;
		this.tourClient = tourClient;
	}

	public void enqueue(Map<String, Object> change) {
		int booking = n(change, "booking_id");
		
		// Get booking basic info
		var bookingRow = jdbc.queryForMap("SELECT account_id, tour_departure_id FROM booking WHERE id=?", booking);
		int accountId = n(bookingRow, "account_id");
		int departureId = n(bookingRow, "tour_departure_id");
		
		// Get user info via Auth internal API
		String email, username;
		try {
			Map<String, Object> user = authClient.getUserById((long) accountId);
			email = (String) user.get("email");
			username = (String) user.get("username");
		} catch (Exception e) {
			throw new IllegalStateException("Cannot fetch user info for booking " + booking, e);
		}
		
		// Get tour info via Tour internal API
		String tourTitle;
		try {
			Map<String, Object> departure = tourClient.getDeparture(departureId);
			tourTitle = (String) departure.get("tour_id") != null ? "Tour #" + departure.get("tour_id") : "Tour";
		} catch (Exception e) {
			throw new IllegalStateException("Cannot fetch tour info for booking " + booking, e);
		}

		String explanation = String.valueOf(change.get("reason"));
		if (change.get("shop_change_id") != null) {
			var shopChange = jdbc.queryForMap(
					"SELECT response_deadline FROM departure_change WHERE id=?", change.get("shop_change_id"));
			explanation += " Hạn phản hồi trực tuyến: "
					+ date(shopChange.get("response_deadline")).format(DEADLINE_FORMAT)
					+ " (giờ Việt Nam). Quá hạn vui lòng liên hệ shop để được hỗ trợ.";
		}

		var payload = new MailPayload(
				1,
				username,
				tourTitle,
				n(change, "target_departure_id"),
				date(change.get("target_departure_at")),
				jdbc.queryForObject("SELECT COUNT(*) FROM customer WHERE booking_id=?", Integer.class, booking),
				null,
				null,
				null,
				null,
				null,
				null,
				((Number) change.get("id")).longValue(),
				date(change.get("source_departure_at")),
				explanation,
				String.valueOf(change.get("status")),
				(String) change.get("refund_reference"));

		String idempotencyKey = "booking:" + booking + ":change:" + change.get("id") + ":"
				+ change.get("status") + ":" + v(change);
		outbox.enqueue(
				idempotencyKey,
				MailEventType.BOOKING_CHANGE_UPDATED,
				EVENT_SOURCE,
				booking,
				n(change, "account_id"),
				email,
				payload,
				LocalDateTime.now(clock));
	}
}