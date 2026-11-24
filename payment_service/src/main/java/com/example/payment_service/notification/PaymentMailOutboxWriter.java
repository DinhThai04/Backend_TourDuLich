package com.example.payment_service.notification;

import com.example.payment_service.outbox.OutboxPublisher;
import com.example.payment_service.repository.httpclient.AuthInternalClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes a durable payment-outbox event; Notification owns mail delivery and SMTP.
 * The caller's settlement transaction therefore never performs HTTP inside the database transaction.
 */
@Component
@Slf4j
public class PaymentMailOutboxWriter {
	private final JdbcTemplate jdbc;
	private final OutboxPublisher outbox;
	private final AuthInternalClient authClient;

	public PaymentMailOutboxWriter(JdbcTemplate jdbc, OutboxPublisher outbox, AuthInternalClient authClient) {
		this.jdbc = jdbc;
		this.outbox = outbox;
		this.authClient = authClient;
	}

	public void receipt(int bookingId, String ref, long amount, LocalDateTime paidAt, LocalDateTime now, String provider) {
		write(bookingId, "PAYMENT_SUCCEEDED", "booking:" + bookingId + ":paid:" + provider + ":" + ref,
				paymentSubject(bookingId), paymentHtml(bookingId, amount, ref, paidAt, provider),
				paymentText(bookingId, amount, ref, paidAt, provider), amount, ref, paidAt, now);
	}

	public void expired(int bookingId, LocalDateTime now) {
		write(bookingId, "BOOKING_EXPIRED", "booking:" + bookingId + ":booking_expired",
				"SGU Tour - Booking #" + bookingId + " expired",
				"<p>Your booking <b>#" + bookingId + "</b> expired because payment was not completed in time.</p>",
				"Your booking #" + bookingId + " expired because payment was not completed in time.",
				null, null, null, now);
	}

	private void write(int bookingId, String type, String eventKey, String subject, String html, String text,
			Long amount, String ref, LocalDateTime paidAt, LocalDateTime now) {
		var paymentRows = jdbc.queryForList("""
				SELECT account_id FROM payment WHERE booking_id=? ORDER BY id DESC LIMIT 1
				""", bookingId);
		if (paymentRows.isEmpty()) {
			paymentRows = jdbc.queryForList("""
					SELECT account_id FROM vnpay_transaction WHERE booking_id=?
					UNION ALL
					SELECT account_id FROM momo_transaction WHERE booking_id=?
					LIMIT 1
					""", bookingId, bookingId);
		}
		if (paymentRows.isEmpty()) {
			log.warn("Skipping {} mail for booking {}: payment row not found", type, bookingId);
			return;
		}
		int accountId = ((Number) paymentRows.getFirst().get("account_id")).intValue();

		String email;
		String username;
		try {
			Map<String, Object> user = authClient.getUserById((long) accountId);
			email = (String) user.get("email");
			username = (String) user.get("username");
		} catch (Exception exception) {
			log.warn("Skipping {} mail for booking {}: cannot fetch user info ({})", type, bookingId,
					exception.getClass().getSimpleName());
			return;
		}
		if (email == null || email.isBlank()) {
			log.warn("Skipping {} mail for booking {}: user has no email", type, bookingId);
			return;
		}

		var payload = new LinkedHashMap<String, Object>();
		payload.put("eventKey", eventKey);
		payload.put("sourceService", "payment-service");
		payload.put("bookingId", bookingId);
		payload.put("accountId", accountId);
		payload.put("recipient", email);
		payload.put("customerName", username);
		payload.put("subject", subject);
		payload.put("html", html);
		payload.put("text", text);
		payload.put("scheduledAt", now.toString());
		payload.put("amountVnd", amount);
		payload.put("transactionRef", ref);
		payload.put("paidAt", paidAt == null ? null : paidAt.toString());

		outbox.writeToOutbox("notification.mail.v1", "Booking", String.valueOf(bookingId),
				null, null, payload);
	}

	private static String paymentSubject(int bookingId) {
		return "SGU Tour - Payment received for booking #" + bookingId;
	}

	private static String paymentHtml(int bookingId, long amount, String ref, LocalDateTime paidAt, String provider) {
		return "<p>We received your payment for booking <b>#" + bookingId + "</b>.</p>"
				+ "<p>Amount: <b>" + amount + " VND</b></p>"
				+ "<p>Provider: " + escapeHtml(provider) + "<br>Transaction: " + escapeHtml(ref)
				+ (paidAt == null ? "" : "<br>Paid at: " + escapeHtml(paidAt.toString())) + "</p>";
	}

	private static String paymentText(int bookingId, long amount, String ref, LocalDateTime paidAt, String provider) {
		return "We received your payment for booking #" + bookingId + ".\n"
				+ "Amount: " + amount + " VND\n"
				+ "Provider: " + provider + "\n"
				+ "Transaction: " + ref + "\n"
				+ (paidAt == null ? "" : "Paid at: " + paidAt + "\n");
	}

	private static String escapeHtml(String value) {
		return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
