package com.example.booking_service.change;

import com.example.booking_service.config.BookingMailOutboxPublisher;
import com.example.booking_service.notification.MailEventType;
import com.example.booking_service.repository.httpclient.AuthInternalClient;
import com.example.booking_service.repository.httpclient.TourInternalClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.example.booking_service.change.ChangeLedger.date;
import static com.example.booking_service.change.ChangeLedger.n;
import static com.example.booking_service.change.ChangeLedger.v;

@Component
public class ChangeMailEvents {

	private static final String EVENT_SOURCE = "booking_service";
	private static final DateTimeFormatter DEADLINE_FORMAT = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");

	private final JdbcTemplate jdbc;
	private final BookingMailOutboxPublisher publisher;
	private final Clock clock;
	private final AuthInternalClient authClient;
	private final TourInternalClient tourClient;

	public ChangeMailEvents(JdbcTemplate jdbc, BookingMailOutboxPublisher publisher, Clock clock,
                            AuthInternalClient authClient, TourInternalClient tourClient) {
		this.jdbc = jdbc;
		this.publisher = publisher;
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
			explanation += " Hạn phản hồi trả trước tuyến: "
					+ date(shopChange.get("response_deadline")).format(DEADLINE_FORMAT)
					+ " (giờ Việt Nam). Quý hẹn vui lòng liên hệ shop để được hỗ trợ.";
		}

		String eventKey = "booking:" + booking + ":change:" + change.get("id") + ":"
				+ change.get("status") + ":" + v(change);
		String subject = "SGU Tour - Đơn hàng #" + booking + " đã thay đổi";
		String html = "<p>Xin chào <b>" + escapeHtml(username) + "</b>,</p>"
				+ "<p>Đơn hàng <b>#" + booking + "</b> (" + escapeHtml(tourTitle) + ") đã có thay đổi.</p>"
			 + "<p>" + escapeHtml(explanation) + "</p>";
		String text = "Xin chào " + username + ",\nĐơn hàng #" + booking + " (" + tourTitle + ") đã có thay đổi.\n"
				+ explanation + "\n";

		var payload = new LinkedHashMap<String, Object>();
		payload.put("eventKey", eventKey);
		payload.put("eventType", MailEventType.BOOKING_CHANGE_UPDATED.name());
		payload.put("sourceService", EVENT_SOURCE);
		payload.put("bookingId", booking);
		payload.put("accountId", accountId);
		payload.put("recipient", email);
		payload.put("subject", subject);
		payload.put("html", html);
		payload.put("text", text);
		payload.put("scheduledAt", LocalDateTime.now(clock).format(DateTimeFormatter.ISO_DATE_TIME));
		payload.put("changeId", ((Number) change.get("id")).longValue());
		payload.put("sourceDepartureAt", date(change.get("source_departure_at")) != null
				? date(change.get("source_departure_at")).format(DateTimeFormatter.ISO_DATE_TIME) : null);
		payload.put("targetDepartureAt", date(change.get("target_departure_at")) != null
				? date(change.get("target_departure_at")).format(DateTimeFormatter.ISO_DATE_TIME) : null);
		payload.put("changeExplanation", explanation);
		payload.put("changeStatus", String.valueOf(change.get("status")));
		payload.put("refundReference", change.get("refund_reference"));

        String payloadJson;
        try {
            payloadJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize mail payload for booking " + booking, e);
        }
        publisher.sendNow(payloadJson);
	}

	private static String escapeHtml(String value) {
		if (value == null) return "";
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}