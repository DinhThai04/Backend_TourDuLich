package com.example.booking_service.change;

import com.example.booking_service.repository.httpclient.InvoiceInternalClient;
import com.example.booking_service.repository.httpclient.PaymentInternalClient;
import com.example.booking_service.repository.httpclient.PricingInternalClient;
import com.example.booking_service.repository.httpclient.TourInternalClient;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Component
public class ChangeLedger {

	private static final List<String> ACTIVE_HOLD_STATUSES = List.of("PAID", "HELD", "CHECKOUT");

	private final JdbcTemplate jdbc;
	private final TourInternalClient tourClient;
	private final PricingInternalClient pricingClient;
	private final PaymentInternalClient paymentClient;
	private final InvoiceInternalClient invoiceClient;

	public ChangeLedger(JdbcTemplate jdbc, TourInternalClient tourClient, PricingInternalClient pricingClient,
			PaymentInternalClient paymentClient, InvoiceInternalClient invoiceClient) {
		this.jdbc = jdbc;
		this.tourClient = tourClient;
		this.pricingClient = pricingClient;
		this.paymentClient = paymentClient;
		this.invoiceClient = invoiceClient;
	}

	public static void require(boolean condition, String message) {
		if (!condition) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, message);
		}
	}

	/** A row the caller asked for by id either is or is not there; that is 404, not a conflict. */
	public static void requireFound(boolean condition, String message) {
		if (!condition) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, message);
		}
	}

	public static int n(Map<String, Object> row, String key) {
		return ((Number) row.get(key)).intValue();
	}

	public static long v(Map<String, Object> row) {
		return ((Number) row.get("version")).longValue();
	}

	public static LocalDateTime date(Object value) {
		return value instanceof LocalDateTime time ? time : ((Timestamp) value).toLocalDateTime();
	}

	public Map<String, Object> booking(int id) {
		var rows = jdbc.queryForList("SELECT * FROM booking WHERE id=?", id);
		requireFound(rows.size() == 1, "Không tìm thấy booking");
		return rows.getFirst();
	}

	public Map<Integer, Map<String, Object>> lockDepartures(int... ids) {
		return departures(true, ids);
	}

	/** Read-only twin of {@link #lockDepartures}: previews must not block checkouts on the departure. */
	public Map<Integer, Map<String, Object>> readDepartures(int... ids) {
		return departures(false, ids);
	}

	private Map<Integer, Map<String, Object>> departures(boolean forUpdate, int... ids) {
		var departures = new TreeMap<Integer, Map<String, Object>>();
		for (int id : Arrays.stream(ids).distinct().sorted().toArray()) {
			Map<String, Object> departure;
			try {
				departure = tourClient.getDeparture(id);
			} catch (Exception e) {
				throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lịch khởi hành");
			}
			requireFound(departure != null && !departure.isEmpty(), "Không tìm thấy lịch khởi hành");
			departures.put(id, departure);
		}
		return departures;
	}

	public Map<String, Object> lockBooking(int id, int source) {
		var rows = jdbc.queryForList("SELECT * FROM booking WHERE id=? FOR UPDATE", id);
		requireFound(rows.size() == 1, "Không tìm thấy booking");
		require(n(rows.getFirst(), "tour_departure_id") == source,
				"Booking đã chuyển lịch; tải lại dữ liệu");
		return rows.getFirst();
	}

	public Map<String, Object> reservation(int booking) {
		var rows = jdbc.queryForList("SELECT * FROM booking_reservation WHERE booking_id=? FOR UPDATE", booking);
		return rows.isEmpty() ? null : rows.getFirst();
	}

	public void clearTransactions(int booking) {
		Map<String, Object> ev;
		try {
			ev = paymentClient.getBookingPaymentEvidence(booking);
		} catch (Exception e) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Không thể xác minh trạng thái giao dịch tại payment-service");
		}
		require(ev != null && !Boolean.TRUE.equals(ev.get("openReconciliation")), "Booking đang cần đối soát");
		require(ev != null && !Boolean.TRUE.equals(ev.get("inFlight")),
				"Giao dịch thanh toán chưa có kết quả rõ ràng; hết hạn link chưa đủ để chuyển lịch");
	}

	public BigDecimal paid(int booking, int owner) {
		Map<String, Object> inv;
		Map<String, Object> pay;
		try {
			inv = invoiceClient.getInvoiceByBookingId(booking);
		} catch (Exception e) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Chứng từ hóa đơn chưa đủ hoặc không thể xác minh");
		}
		try {
			pay = paymentClient.getPaymentStatus(booking);
		} catch (Exception e) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Chứng từ thanh toán chưa đủ hoặc không thể xác minh");
		}
		require(inv != null && "PAID".equals(inv.get("status")) && n(inv, "accountId") == owner,
				"Chứng từ thanh toán chưa đủ hoặc bị trùng; cần đối soát");
		require(pay != null && "PAID".equals(pay.get("status")) && n(pay, "accountId") == owner,
				"Chứng từ thanh toán chưa đủ hoặc bị trùng; cần đối soát");
		var invoiceAmount = new BigDecimal(inv.get("totalBookingTourExpense").toString());
		var paymentAmount = new BigDecimal(pay.get("totalBookingTourExpense").toString());
		require(invoiceAmount.signum() > 0 && invoiceAmount.compareTo(paymentAmount) == 0,
				"Tiền hóa đơn và payment không khớp");
		return invoiceAmount;
	}

	public boolean hasPaid(int booking) {
		try {
			Map<String, Object> ev = paymentClient.getBookingPaymentEvidence(booking);
			if (ev != null && (Boolean.TRUE.equals(ev.get("paidPayment")) || Boolean.TRUE.equals(ev.get("paidProvider")))) {
				return true;
			}
		} catch (Exception e) {
			return true;
		}
		try {
			Map<String, Object> inv = invoiceClient.getInvoiceByBookingId(booking);
			return inv != null && "PAID".equals(inv.get("status"));
		} catch (feign.FeignException.NotFound ignored) {
			return false;
		} catch (Exception e) {
			return true;
		}
	}

	public int people(int booking) {
		int count = jdbc.queryForObject("SELECT COUNT(*) FROM customer WHERE booking_id=?", Integer.class, booking);
		require(count > 0, "Booking thiếu hành khách");
		return count;
	}

	public int assertLedger(int departureId) {
		Map<String, Object> departure;
		try {
			departure = tourClient.getDeparture(departureId);
		} catch (Exception e) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lịch khởi hành");
		}
		require(departure.get("capacity") != null, "Admin cần xác nhận tổng sức chứa trước khi chuyển lịch");
		int occupied = 0;

		var bookings = jdbc.queryForList("SELECT * FROM booking WHERE tour_departure_id=? ORDER BY id",
				departureId);
		for (var booking : bookings) {
			int bookingId = n(booking, "id");
			boolean money = hasPaid(bookingId);
			boolean refunded = jdbc.queryForObject(
					"SELECT COUNT(*) FROM booking_change WHERE booking_id=? AND status='REFUNDED'",
					Integer.class, bookingId) > 0;
			var holds = jdbc.queryForList("SELECT * FROM booking_reservation WHERE booking_id=?", bookingId);

			if (holds.isEmpty()) {
				if (money && !refunded) {
					require("CONFIRMED".equals(booking.get("status")), "Booking hủy còn chứng từ chưa xử lý");
					occupied += people(bookingId);
				}
				continue;
			}

			var hold = holds.getFirst();
			String status = String.valueOf(hold.get("status"));
			if (ACTIVE_HOLD_STATUSES.contains(status)) {
				boolean paidHold = "PAID".equals(status);
				// An unpaid hold legitimately belongs to PENDING_PAYMENT; only money may assert CONFIRMED.
				require(("CONFIRMED".equals(booking.get("status"))
								|| (!paidHold && "PENDING_PAYMENT".equals(booking.get("status"))))
								&& n(hold, "departure_id") == departureId
								&& date(hold.get("departure_at")).equals(date(departure.get("departure_date")))
								&& n(hold, "seats") == people(bookingId),
						"Dữ liệu giữ chỗ không khớp; cần kiểm tra");
				require(paidHold == money, "Giữ chỗ và thanh toán mâu thuẫn");
				occupied += n(hold, "seats");
			} else {
				require(!money || refunded, "Chỗ đã trả nhưng tiền chưa được giải quyết");
			}
		}

		require(occupied + n(departure, "available_seats") == n(departure, "capacity"),
				"Số chỗ không cân bằng; không tự sửa trong lúc chuyển lịch");
		return occupied;
	}

	public void compatible(int sourceDepartureId, int targetDepartureId, LocalDateTime now) {
		Map<String, Object> source, target;
		try {
			source = tourClient.getDeparture(sourceDepartureId);
			target = tourClient.getDeparture(targetDepartureId);
		} catch (Exception e) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lịch khởi hành");
		}
		require(n(source, "id") != n(target, "id") && n(source, "tour_id") == n(target, "tour_id"),
				"Chỉ chuyển sang lịch khác của cùng tour");
		require(date(target.get("departure_date")).isAfter(now), "Lịch mới đã khởi hành");
		require(Duration.between(date(source.get("departure_date")), date(source.get("return_date")))
						.equals(Duration.between(date(target.get("departure_date")), date(target.get("return_date")))),
				"Hai lịch khác thời lượng; cần hỗ trợ thủ công");
	}

	public void samePrice(int booking, int departureId, BigDecimal amount) {
		// Get tour info via Tour internal API
		Map<String, Object> departure;
		try {
			departure = tourClient.getDeparture(departureId);
		} catch (Exception e) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lịch khởi hành");
		}
		int tourId = n(departure, "tour_id");
		
		// Get price info via Pricing internal API
		Map<String, Object> price;
		try {
			price = pricingClient.getPriceById((long) tourId);
		} catch (Exception e) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Tour chưa có bảng giá để chuyển lịch");
		}
		if (price == null || price.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Tour chưa có bảng giá để chuyển lịch");
		}
		
		BigDecimal total = BigDecimal.ZERO;
		var customers = jdbc.queryForList("SELECT booking_type FROM customer WHERE booking_id=?", booking);
		for (var customer : customers) {
			String column = priceColumn(String.valueOf(customer.get("booking_type")));
			// Pricing internal API uses camelCase
			String apiColumn = switch (column) {
				case "adult_price" -> "adultPrice";
				case "child_price" -> "childPrice";
				case "toddler_price" -> "toddlerPrice";
				case "infant_price" -> "infantPrice";
				case "single_supplement_price" -> "singleSupplementPrice";
				default -> column;
			};
			Object value = price.get(apiColumn);
			if (value != null) {
				total = total.add(new BigDecimal(value.toString()));
			}
		}
		require(total.compareTo(amount) == 0,
				"Giá hiện tại khác số tiền đã thanh toán; cần thỏa thuận chênh lệch thủ công");
	}

	private static String priceColumn(String bookingType) {
		return switch (bookingType) {
			case "ADULT" -> "adult_price";
			case "CHILD" -> "child_price";
			case "TODDLER" -> "toddler_price";
			case "INFANT" -> "infant_price";
			default -> throw new IllegalArgumentException("Loại khách không hợp lệ");
		};
	}
}