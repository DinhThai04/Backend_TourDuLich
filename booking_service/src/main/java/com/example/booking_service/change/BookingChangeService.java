package com.example.booking_service.change;

import com.example.booking_service.paging.PageSlice;
import com.example.booking_service.reservation.BookingAccess;
import com.example.booking_service.reservation.ReservationPolicy;
import com.example.booking_service.repository.httpclient.AuthInternalClient;
import com.example.booking_service.repository.httpclient.TourInternalClient;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.example.booking_service.change.ChangeLedger.date;
import static com.example.booking_service.change.ChangeLedger.n;
import static com.example.booking_service.change.ChangeLedger.require;
import static com.example.booking_service.change.ChangeLedger.requireFound;
import static com.example.booking_service.change.ChangeLedger.v;

/** All stock, consent, audit and outbox writes share the booking database transaction.
 * Lock order: departures in ID order, booking, reservation, change. Never call external providers here.
 */
@Service
public class BookingChangeService {

	private static final long CUSTOMER_CUTOFF_HOURS = 72;
	private static final int MAX_CHANGE_OPTIONS = 200;
	private static final String OVERDUE_NOTE = "Quá hạn xử lý trước 72 giờ khởi hành; yêu cầu được tự động từ chối";
	private static final List<String> OPEN_CHANGE_STATUSES = List.of("PENDING", "SUPPORT_REQUIRED");
	private static final List<String> OCCUPYING_HOLD_STATUSES = List.of("PAID", "HELD", "CHECKOUT");
	private static final Set<String> SHOP_INCIDENT_REASONS = Set.of(
			"NATURAL_DISASTER", "OFFICIAL_SAFETY_RESTRICTION", "UNAVOIDABLE_TRANSPORT_DISRUPTION");
	private static final Set<String> CHANGE_PERMISSIONS = Set.of(
			"CHANGES_READ", "CHANGES_REVIEW", "SHOP_CHANGE_CREATE", "REFUNDS_RECORD");

	private final JdbcTemplate jdbc;
	private final ChangeLedger ledger;
    private final com.example.booking_service.operations.AdminAuditEvents adminAudit;
	private final BookingAccess access;
	private final ReservationPolicy policy;
	private final ChangeMailEvents mail;
	private final Clock clock;
	private final TransactionTemplate tx;
	private final AuthInternalClient authClient;
	private final TourInternalClient tourClient;
	private final com.example.booking_service.repository.httpclient.PaymentInternalClient paymentClient;

	public BookingChangeService(JdbcTemplate jdbc, ChangeLedger ledger, BookingAccess access,
								ReservationPolicy policy, ChangeMailEvents mail, Clock clock,
								PlatformTransactionManager manager, AuthInternalClient authClient,
								TourInternalClient tourClient,
								com.example.booking_service.repository.httpclient.PaymentInternalClient paymentClient,
                                com.example.booking_service.operations.AdminAuditEvents adminAudit) {
		this.jdbc = jdbc;
		this.ledger = ledger;
		this.access = access;
		this.policy = policy;
		this.mail = mail;
		this.clock = clock;
		this.tx = new TransactionTemplate(manager);
		this.authClient = authClient;
		this.tourClient = tourClient;
		this.paymentClient = paymentClient;
        this.adminAudit = adminAudit;
	}

	public List<Map<String, Object>> mine() {
		return mine(null, null);
	}

	public List<Map<String, Object>> mine(Integer page, Integer size) {
		return mine(page, size, null);
	}

	public List<Map<String, Object>> mine(Integer page, Integer size, Integer bookingId) {
		PageSlice slice = PageSlice.of(page, size);
		var rows = jdbc.queryForList("""
				SELECT c.*,dc.response_deadline FROM booking_change c
				LEFT JOIN departure_change dc ON dc.id=c.shop_change_id
				WHERE c.account_id=? AND (? IS NULL OR c.booking_id=?)
				ORDER BY c.id DESC LIMIT ? OFFSET ?
				""", access.accountId(), bookingId, bookingId, slice.size(), slice.offset());
		
		// Enrich with tour info via Tour internal API
		for (var row : rows) {
			Integer sourceDepartureId = n(row, "source_departure_id");
			if (sourceDepartureId != null) {
				try {
					Map<String, Object> departure = tourClient.getDeparture(sourceDepartureId);
					if (departure != null) {
						row.put("tour_title", Objects.requireNonNullElse(departure.get("tour_title"), "Tour"));
					}
				} catch (Exception ignored) {
					row.put("tour_title", "Tour");
				}
			}
		}
		return rows;
	}

	public List<Map<String, Object>> eligible() {
		return eligible(null, null);
	}

	public List<Map<String, Object>> eligible(Integer page, Integer size) {
		PageSlice slice = PageSlice.of(page, size);
		LocalDateTime cutoff = now().plusHours(CUSTOMER_CUTOFF_HOURS);
		var rows = jdbc.queryForList("""
				SELECT b.id, b.tour_departure_id FROM booking b
				WHERE b.account_id=? AND b.status='CONFIRMED' AND b.tour_departure_id IS NOT NULL
				AND NOT EXISTS(SELECT 1 FROM booking_change c WHERE c.booking_id=b.id AND c.active_key=1)
				ORDER BY b.id DESC LIMIT ? OFFSET ?
				""", access.accountId(), slice.size(), slice.offset());

		List<Map<String, Object>> filtered = new ArrayList<>();
		for (var row : rows) {
			int bookingId = n(row, "id");
			if (!ledger.hasPaid(bookingId)) continue;
			Integer departureId = n(row, "tour_departure_id");
			if (departureId != null) {
				try {
					Map<String, Object> departure = tourClient.getDeparture(departureId);
					if (departure != null) {
						String opStatus = (String) departure.get("operational_status");
						LocalDateTime departureDate = ChangeLedger.date(departure.get("departure_date"));
						if ("OPEN".equals(opStatus) && departureDate != null && !departureDate.isBefore(cutoff)) {
							row.put("departure_date", departureDate);
							row.put("tour_title", Objects.requireNonNullElse(departure.get("tour_title"), "Tour"));
							filtered.add(row);
						}
					}
				} catch (feign.FeignException.NotFound ignored) {
					// A departure that no longer exists cannot accept a request.
				} catch (RuntimeException e) {
					throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Chưa kiểm tra được lịch khởi hành", e);
				}
			}
		}
		return filtered;
	}

	public List<Map<String, Object>> options(int booking) {
		var bookingRow = ledger.booking(booking);
		requireOwner(n(bookingRow, "account_id"));
		int passengers = jdbc.queryForObject("SELECT COUNT(*) FROM customer WHERE booking_id=?", Integer.class, booking);

		Integer sourceDepartureId = n(bookingRow, "tour_departure_id");
		if (sourceDepartureId == null) return List.of();

		int tourId;
		long sourceDurationMinutes;
		try {
			Map<String, Object> sourceDeparture = tourClient.getDeparture(sourceDepartureId);
			tourId = n(sourceDeparture, "tour_id");
			LocalDateTime sourceDep = ChangeLedger.date(sourceDeparture.get("departure_date"));
			LocalDateTime sourceRet = ChangeLedger.date(sourceDeparture.get("return_date"));
			sourceDurationMinutes = java.time.Duration.between(sourceDep, sourceRet).toMinutes();
		} catch (Exception e) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Không tìm thấy lịch khởi hành gốc");
		}

		List<Map<String, Object>> allDepartures;
		try {
			allDepartures = tourClient.getDeparturesByTour(tourId);
		} catch (Exception e) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Chưa tải được lịch khởi hành thay thế", e);
		}
		LocalDateTime current = now();
		return allDepartures.stream()
				.filter(d -> n(d, "id") != sourceDepartureId)
				.filter(d -> "OPEN".equals(d.get("operational_status")))
				.filter(d -> n(d, "available_seats") >= passengers)
				.filter(d -> {
					LocalDateTime dep = ChangeLedger.date(d.get("departure_date"));
					LocalDateTime ret = ChangeLedger.date(d.get("return_date"));
					return dep != null && ret != null && dep.isAfter(current)
							&& java.time.Duration.between(dep, ret).toMinutes() == sourceDurationMinutes;
				})
				.sorted(java.util.Comparator.comparing(d -> ChangeLedger.date(d.get("departure_date"))))
				.limit(MAX_CHANGE_OPTIONS)
				.toList();
	}

	public Map<String, Object> customer(ChangeRequests.Customer input) {
		requireClientKey(input.clientKey());
		normalizeNote(input.reason());
		var initial = ledger.booking(input.bookingId());
		requireOwner(n(initial, "account_id"));

		return tx.execute(status -> {
			var departures = ledger.lockDepartures(n(initial, "tour_departure_id"), input.targetId());

			// Replay before locking the booking: once the shop approved, the booking points at the
			// new departure and lockBooking would reject a legitimate retry of the original request.
			var retries = jdbc.queryForList("SELECT * FROM booking_change WHERE client_key=?", input.clientKey());
			if (!retries.isEmpty()) {
				var retry = retries.getFirst();
				require(n(retry, "booking_id") == input.bookingId()
								&& n(retry, "target_departure_id") == input.targetId()
								&& "CUSTOMER".equals(retry.get("kind")),
						"Mã yêu cầu đã được dùng");
				return retry;
			}

			var booking = ledger.lockBooking(input.bookingId(), n(initial, "tour_departure_id"));
			requireOwner(n(booking, "account_id"));

			var source = departures.get(n(booking, "tour_departure_id"));
			var target = departures.get(input.targetId());
			requireActiveBooking(booking);
			require("OPEN".equals(source.get("operational_status"))
							&& "OPEN".equals(target.get("operational_status")),
					"Lịch đang được xử lý sự cố");
			require(!now().isAfter(date(source.get("departure_date")).minusHours(CUSTOMER_CUTOFF_HOURS)),
					"Chỉ nhận yêu cầu dời lịch trước giờ khởi hành ít nhất 72 giờ");

			ledger.clearTransactions(input.bookingId());
			ledger.compatible(n(source, "id"), n(target, "id"), now());
			var amount = ledger.paid(input.bookingId(), n(booking, "account_id"));
			ledger.samePrice(input.bookingId(), n(source, "id"), amount);
			ledger.assertLedger(n(source, "id"));
			ledger.assertLedger(n(target, "id"));
			rejectOverdueCustomerChanges(input.bookingId());
			require(countActiveChanges(input.bookingId()) == 0, "Booking đã có yêu cầu đang xử lý");

			long id = insertChange(input.clientKey(), input.bookingId(), n(booking, "account_id"),
					"CUSTOMER", null, source, target, input.reason());
			return findChange(id);
		});
	}

	public Map<String, Object> admin(Integer tour, Integer page, Integer size) {
		return admin(tour, page, size, null);
	}

	public Map<String, Object> admin(Integer tour, Integer page, Integer size, Long requestId) {
		checkPermission("CHANGES_READ");
		PageSlice slice = PageSlice.of(page, size);
		Set<Integer> tourDepartureIds = null;
		if (tour != null) {
			try {
				tourDepartureIds = tourClient.getDeparturesByTour(tour).stream()
						.map(d -> n(d, "id"))
						.collect(java.util.stream.Collectors.toSet());
			} catch (Exception ignored) {
				tourDepartureIds = Set.of();
			}
		}

		var requests = jdbc.queryForList("""
				SELECT c.* FROM booking_change c
				WHERE (? IS NULL OR c.id=?)
				ORDER BY c.id DESC LIMIT ? OFFSET ?
				""", requestId, requestId, slice.size(), slice.offset());

		final Set<Integer> depFilter = tourDepartureIds;
		if (depFilter != null) {
			requests = requests.stream()
					.filter(row -> depFilter.contains(n(row, "source_departure_id")))
					.toList();
		}

		for (var row : requests) {
			Integer sourceDepartureId = n(row, "source_departure_id");
			if (sourceDepartureId != null) {
				try {
					Map<String, Object> departure = tourClient.getDeparture(sourceDepartureId);
					if (departure != null) {
						row.put("tour_title", Objects.requireNonNullElse(departure.get("tour_title"), "Tour"));
					}
				} catch (Exception ignored) {
					row.put("tour_title", "Tour");
				}
			}
			try {
				Map<String, Object> pay = paymentClient.getPaymentStatus(n(row, "booking_id"));
				if (pay != null && "PAID".equals(pay.get("status"))) {
					row.put("paid_amount", pay.get("totalBookingTourExpense"));
				}
			} catch (Exception ignored) {
			}
		}

		attachEvents(requests);
		if (requestId != null) return Map.of("requests", requests, "proposals", List.of());
		var proposals = jdbc.queryForList("""
				SELECT c.* FROM departure_change c
				ORDER BY c.id DESC LIMIT ? OFFSET ?
				""", slice.size(), slice.offset());
		if (depFilter != null) {
			proposals = proposals.stream()
					.filter(row -> depFilter.contains(n(row, "source_departure_id")))
					.toList();
		}
		return Map.of("requests", requests, "proposals", proposals);
	}

	public Map<String, Object> pending(int page) {
		return pending(page, null, null, null);
	}

	public Map<String, Object> pending(int page, Integer bookingId, java.time.LocalDate day, Boolean customerAction) {
		return pending(page, bookingId, day, customerAction, 5);
	}
	public Map<String, Object> pending(int page, Integer bookingId, java.time.LocalDate day, Boolean customerAction, int size) {
		checkPermission("CHANGES_READ");
		PageSlice slice = PageSlice.of(page, size);
		var filters = new StringBuilder();
		var arguments = new ArrayList<>();
		if (bookingId != null) {
			filters.append(" AND c.booking_id=?");
			arguments.add(bookingId);
		}
		if (day != null) {
			filters.append(" AND c.source_departure_at>=? AND c.source_departure_at<?");
			arguments.add(day.atStartOfDay());
			arguments.add(day.plusDays(1).atStartOfDay());
		}
		if (customerAction != null) {
			filters.append(customerAction ? " AND c.kind='SHOP' AND c.status='PENDING'"
					: " AND NOT (c.kind='SHOP' AND c.status='PENDING')");
		}
		String scope = """
			FROM booking_change c
			WHERE c.active_key IS NOT NULL AND (
				(c.kind='CUSTOMER' AND c.status='PENDING')
				OR (c.kind='SHOP' AND c.status IN ('PENDING','ACCEPTED','SUPPORT_REQUIRED'))
			)
			""" + filters;
		Long total = jdbc.queryForObject("SELECT COUNT(*) " + scope, Long.class, arguments.toArray());
		arguments.add(slice.size());
		arguments.add(slice.offset());
		var requests = jdbc.queryForList("""
			SELECT c.id,c.booking_id,c.kind,c.status,c.source_departure_at,c.source_departure_id,c.created_at
			%s
			ORDER BY CASE WHEN c.status='SUPPORT_REQUIRED' THEN 0
						  WHEN c.kind='SHOP' AND c.status='PENDING' THEN 2 ELSE 1 END,
					c.created_at DESC,c.id DESC LIMIT ? OFFSET ?
			""".formatted(scope), arguments.toArray());

		// Enrich with tour info via Tour internal API
		for (var row : requests) {
			Integer sourceDepartureId = n(row, "source_departure_id");
			if (sourceDepartureId != null) {
				try {
					Map<String, Object> departure = tourClient.getDeparture(sourceDepartureId);
					if (departure != null) {
						row.put("tour_id", departure.get("tour_id"));
						row.put("tour_title", Objects.requireNonNullElse(departure.get("tour_title"), "Tour"));
					}
				} catch (Exception ignored) {
					row.put("tour_title", "Tour");
				}
			}
		}
		
		requests.forEach(row -> {
			for (String field : List.of("source_departure_at", "created_at"))
				row.computeIfPresent(field, (name, value) -> date(value).toString());
		});
		return Map.of("items", requests, "hasNext", total != null && total > slice.offset() + slice.size(), "total", total == null ? 0 : total);
	}

	/** One query for every request on the page instead of one query per request. */
	private void attachEvents(List<Map<String, Object>> requests) {
		if (requests.isEmpty()) return;
		String placeholders = String.join(",", Collections.nCopies(requests.size(), "?"));
		Object[] ids = requests.stream().map(request -> request.get("id")).toArray();
		var events = jdbc.queryForList("""
				SELECT booking_change_id,actor_id,action,note,created_at
				FROM booking_change_event WHERE booking_change_id IN (%s)
				ORDER BY booking_change_id,id
				""".formatted(placeholders), ids);

		Map<Long, List<Map<String, Object>>> byChange = new HashMap<>();
		for (var event : events) {
			long changeId = ((Number) event.remove("booking_change_id")).longValue();
			byChange.computeIfAbsent(changeId, ignored -> new ArrayList<>()).add(event);
		}
		for (var request : requests) {
			long changeId = ((Number) request.get("id")).longValue();
			request.put("events", byChange.getOrDefault(changeId, List.of()));
		}
	}

	public Map<String, Object> shop(ChangeRequests.Shop input, boolean preview) {
		checkPermission("SHOP_CHANGE_CREATE");
		return tx.execute(status -> {
            // Serialize declaring an incident with confirming an actual departure.
			if (!preview) {
                jdbc.update("""
                        INSERT INTO departure_manifest(departure_id) VALUES(?)
                        ON DUPLICATE KEY UPDATE departure_id=departure_id
                        """, input.sourceId());
				var previous = jdbc.queryForList(
						"SELECT * FROM departure_change WHERE client_key=?", input.clientKey());
				if (!previous.isEmpty()) {
					require(n(previous.getFirst(), "source_departure_id") == input.sourceId(),
							"Mã yêu cầu đã được dùng");
					return previous.getFirst();
				}
			}

            requireUnstartedManifest(input.sourceId(), !preview);
            var source = ledger.readDepartures(input.sourceId()).get(input.sourceId());
			int occupiedSeats = validateShop(input, source, preview);
			var bookings = jdbc.queryForList(
					"SELECT * FROM booking WHERE tour_departure_id=? AND status='CONFIRMED' ORDER BY id",
					input.sourceId());
			if (preview) {
				return Map.of(
						"bookingCount", bookings.size(),
						"occupiedSeats", occupiedSeats,
						"capacity", input.capacity(),
						"version", v(source));
			}

			var replacement = tourClient.createReplacement(Map.of(
					"tourId", source.get("tour_id"),
					"tourCode", "SGU-R-" + input.clientKey(),
					"departureDate", input.departureAt().toString(),
					"returnDate", input.returnAt().toString(),
					"capacity", input.capacity()));
			int target = n(replacement, "id");
			long root = insert("""
					INSERT INTO departure_change(client_key,source_departure_id,target_departure_id,
					  reason_code,reason,evidence,response_deadline,created_by,created_at)
					VALUES(?,?,?,?,?,?,?,?,?)
					""", input.clientKey(), input.sourceId(), target, input.reasonCode(), input.reason(),
					input.evidence(), input.responseDeadline(), access.accountId(), now());
			tourClient.updateOperationalStatus(input.sourceId(), "SUSPENDED");
            if (adminAudit != null) adminAudit.record(access.accountId(), "DEPARTURE_INCIDENT_DECLARED",
                    "DEPARTURE", input.sourceId(), "Chuyến " + Objects.toString(source.get("tour_code"), "#" + input.sourceId()),
                    "Chuyển chuyến sang xử lý sự cố", input.reason(), Map.of("status", "OPEN"),
                    Map.of("status", "INCIDENT", "caseId", root));

			for (var booking : bookings) {
				var pendingChanges = jdbc.queryForList(
						"SELECT id FROM booking_change WHERE booking_id=? AND active_key=1", booking.get("id"));
				for (var pending : pendingChanges) {
					state(((Number) pending.get("id")).longValue(), "SUPERSEDED", false,
							"Shop buộc phải dừng chuyến cũ; xử lý theo đề xuất mới");
				}
				insertChange(input.clientKey() + ":" + booking.get("id"), n(booking, "id"),
						n(booking, "account_id"), "SHOP", root, source, replacement, input.reason());
			}
			return jdbc.queryForMap("SELECT * FROM departure_change WHERE id=?", root);
		});
	}

	public Map<String, Object> respond(long id, ChangeRequests.Decision input) {
		var before = findChange(id);
		requireOwner(n(before, "account_id"));
		return tx.execute(status -> {
			int target = input.targetId() == null ? n(before, "target_departure_id") : input.targetId();
			var departures = ledger.lockDepartures(
					n(before, "source_departure_id"), n(before, "target_departure_id"), target);
			var booking = ledger.lockBooking(n(before, "booking_id"), n(before, "source_departure_id"));
			requireOwner(n(booking, "account_id"));
			var change = lockedChange(id, input.expectedVersion());

			require("SHOP".equals(change.get("kind"))
							&& OPEN_CHANGE_STATUSES.contains(change.get("status")),
					"Đề xuất không còn chờ phản hồi");
			var root = jdbc.queryForMap(
					"SELECT * FROM departure_change WHERE id=?", change.get("shop_change_id"));
			require(date(root.get("response_deadline")).isAfter(now()),
					"Đã quá hạn phản hồi trực tuyến; liên hệ shop để ghi nhận lựa chọn");
			requireActiveBooking(booking);

			if ("ACCEPT".equals(input.action())) {
				ledger.compatible(n(change, "source_departure_id"), target, now());
				require(target == n(root, "target_departure_id")
								|| "OPEN".equals(departures.get(target).get("operational_status")),
						"Lịch thay thế không hợp lệ");
				var selected = departures.get(target);
				jdbc.update("""
						UPDATE booking_change SET target_departure_id=?,target_departure_at=?,target_return_at=?,
						  consent_source='CUSTOMER',consent_note=? WHERE id=?
						""", target, selected.get("departure_date"), selected.get("return_date"),
						"Khách xác nhận trực tiếp trên tài khoản", id);
				state(id, "ACCEPTED", true, "Khách đồng ý lịch thay thế; chờ shop duyệt và kiểm tra chỗ");
			} else {
				require(List.of("REFUND", "SUPPORT").contains(input.action()), "Lựa chọn không hợp lệ");
				boolean refund = "REFUND".equals(input.action());
				// Repeating the same choice must not overwrite who consented (or send another mail).
				if ("SUPPORT_REQUIRED".equals(change.get("status"))
						&& Boolean.TRUE.equals(change.get("refund_requested")) == refund) {
					return findChange(id);
				}
				jdbc.update("""
						UPDATE booking_change SET refund_requested=?,consent_source='CUSTOMER',consent_note=?
						WHERE id=?
						""", refund, normalizeNote(input.note()), id);
				state(id, "SUPPORT_REQUIRED", true, input.note());
			}
			return findChange(id);
		});
	}

	public Map<String, Object> review(long id, ChangeRequests.Decision input) {
		checkPermission("CHANGES_REVIEW");
		var before = findChange(id);
		return tx.execute(status -> {
			var departures = ledger.lockDepartures(
					n(before, "source_departure_id"), n(before, "target_departure_id"));
			var current = findChange(id);
			if ("APPROVE".equals(input.action()) && "APPROVED".equals(current.get("status"))) {
				return current;
			}
			var booking = ledger.lockBooking(n(before, "booking_id"), n(before, "source_departure_id"));
			var change = lockedChange(id, input.expectedVersion());
			require(change.get("active_key") != null, "Yêu cầu đã kết thúc");

			if ("RECORD_REFUND_REQUEST".equals(input.action())) {
				require("SHOP".equals(change.get("kind")),
						"Chỉ ghi nhận yêu cầu hoàn tiền trong sự cố của shop");
				require(OPEN_CHANGE_STATUSES.contains(change.get("status")),
						"Không thể ghi nhận yêu cầu hoàn tiền ở trạng thái này");
				if (Boolean.TRUE.equals(change.get("refund_requested"))) {
					return findChange(id);
				}
				jdbc.update("""
						UPDATE booking_change SET refund_requested=TRUE,consent_source='STAFF_RECORDED',
						  consent_note=? WHERE id=?
						""", normalizeNote(input.note()), id);
				state(id, "SUPPORT_REQUIRED", true, input.note());
			} else if ("RECORD_CONSENT".equals(input.action())) {
				require("SHOP".equals(change.get("kind"))
								&& OPEN_CHANGE_STATUSES.contains(change.get("status")),
						"Không thể ghi nhận đồng ý ở trạng thái này");
				jdbc.update("""
						UPDATE booking_change SET consent_source='STAFF_RECORDED',consent_note=?,
						  refund_requested=FALSE WHERE id=?
						""", normalizeNote(input.note()), id);
				state(id, "ACCEPTED", true, input.note());
			} else if ("REJECT".equals(input.action())) {
				boolean shop = "SHOP".equals(change.get("kind"));
				state(id, shop ? "SUPPORT_REQUIRED" : "REJECTED", shop, normalizeNote(input.note()));
			} else if ("CANCEL_UNPAID".equals(input.action())) {
				require("SHOP".equals(change.get("kind")),
						"Chỉ xử lý hủy booking bị ảnh hưởng bởi sự cố");
				ledger.clearTransactions(n(booking, "id"));
				require(!ledger.hasPaid(n(booking, "id")), "Booking có tiền, cần xử lý hoàn tiền riêng");
				ledger.assertLedger(n(change, "source_departure_id"));
				releaseAndCancel(booking, departures.get(n(change, "source_departure_id")), "SHOP_DISRUPTION", false);
				state(id, "CANCELLED_BY_SHOP", false, normalizeNote(input.note()));
			} else {
				require("APPROVE".equals(input.action()), "Thao tác không hợp lệ");
				apply(change, booking, departures.get(n(change, "source_departure_id")),
						departures.get(n(change, "target_departure_id")));
                int bookingId = n(booking, "id");
                if (adminAudit != null) adminAudit.record(access.accountId(), "BOOKING_RESCHEDULED", "BOOKING", bookingId,
                        "Đơn #" + bookingId, "Chuyển lịch đặt chỗ", input.note(),
                        Map.of("departureId", n(change, "source_departure_id")),
                        Map.of("departureId", n(change, "target_departure_id")));
			}
			return findChange(id);
		});
	}

	public Map<String, Object> refund(long id, ChangeRequests.Refund input) {
		checkPermission("REFUNDS_RECORD");
		var before = findChange(id);
		return tx.execute(status -> {
			var source = ledger.lockDepartures(n(before, "source_departure_id"))
					.get(n(before, "source_departure_id"));
			var booking = ledger.lockBooking(n(before, "booking_id"), n(before, "source_departure_id"));

			var latest = findChange(id);
			if ("REFUNDED".equals(latest.get("status"))) {
				require(Objects.equals(latest.get("refund_reference"), input.reference())
								&& new BigDecimal(latest.get("refund_amount").toString())
								.compareTo(input.amount()) == 0,
						"Đã ghi nhận hoàn tiền khác");
				return latest;
			}

			var change = lockedChange(id, input.expectedVersion());
			require("SHOP".equals(change.get("kind"))
							&& "SUPPORT_REQUIRED".equals(change.get("status"))
							&& Boolean.TRUE.equals(change.get("refund_requested")),
					"Cần yêu cầu hoàn tiền rõ ràng từ khách trước");
			require(input.reference() != null && !input.reference().isBlank()
							&& input.reference().length() <= 191 && input.amount() != null,
					"Cần số tiền và tham chiếu hoàn tiền hợp lệ");
			require(input.externalRefundCompleted(), "Chỉ ghi nhận sau khi shop đã hoàn tiền thực tế");
			normalizeNote(input.note());

			ledger.clearTransactions(n(booking, "id"));
			int bookingId = n(booking, "id");
			var payment = paymentClient.getPaymentStatus(bookingId);
			// On retry, Payment accepts only the same recorded reference and amount.
			boolean alreadyRecorded = payment != null && "REFUNDED".equals(payment.get("status"));
			if (!alreadyRecorded) ledger.assertLedger(n(source, "id"));
			var amount = alreadyRecorded ? input.amount() : ledger.paid(bookingId, n(booking, "account_id"));
			require(amount.compareTo(input.amount()) == 0,
					"Chỉ ghi nhận hoàn toàn bộ đúng số tiền đã thanh toán");
			require(jdbc.queryForObject("""
							SELECT COUNT(*) FROM booking_change
							WHERE refund_reference=? AND status='REFUNDED' AND id<>?
							""", Integer.class, input.reference(), id) == 0,
					"Tham chiếu hoàn tiền đã dùng cho booking khác");

			paymentClient.refund(bookingId, amount.longValueExact(), input.reference());
			releaseAndCancel(booking, source, "SHOP_REFUNDED", true);
			jdbc.update("""
					UPDATE booking_change SET refund_amount=?,refund_reference=?,refund_recorded_by=?,
					  refund_recorded_at=? WHERE id=?
					""", amount, input.reference(), access.accountId(), now(), id);
			state(id, "REFUNDED", false, input.note());
            if (adminAudit != null) adminAudit.record(access.accountId(), "BOOKING_REFUND_RECORDED", "BOOKING", bookingId,
                    "Đơn #" + bookingId, "Ghi nhận hoàn tiền hồ sơ dời lịch", input.note(),
                    Map.of("status", change.get("status")), Map.of("status", "REFUNDED", "amountVnd", amount, "reference", input.reference()));
			ledger.assertLedger(n(source, "id"));
			return findChange(id);
		});
	}

	public Map<String, Object> activate(long rootId) {
		checkPermission("SHOP_CHANGE_CREATE");
		var root = findDepartureChange(rootId);
		return tx.execute(status -> {
			var departures = ledger.lockDepartures(
					n(root, "source_departure_id"), n(root, "target_departure_id"));
			var current = jdbc.queryForMap(
					"SELECT * FROM departure_change WHERE id=? FOR UPDATE", rootId);
			if ("COMPLETED".equals(current.get("status"))) {
				return current;
			}
			require(jdbc.queryForObject("""
							SELECT COUNT(*) FROM booking
							WHERE tour_departure_id=? AND status='CONFIRMED'
							""", Integer.class, n(root, "source_departure_id")) == 0,
					"Còn khách chưa được xử lý; không tự chuyển hay coi im lặng là đồng ý");
			for (var departure : departures.values()) {
				ledger.assertLedger(n(departure, "id"));
			}
			var pendingChanges = jdbc.queryForList(
					"SELECT id FROM booking_change WHERE shop_change_id=? AND active_key=1", rootId);
			for (var pending : pendingChanges) {
				state(((Number) pending.get("id")).longValue(), "WITHDRAWN", false, "Booking cũ đã kết thúc");
			}
            int sourceId = n(root, "source_departure_id");
            // Open first so a missing pickup point leaves the source incident available for retry.
			tourClient.updateOperationalStatus(n(root, "target_departure_id"), "OPEN");
			tourClient.updateOperationalStatus(sourceId, "CANCELLED");
            var source = departures.get(sourceId);
            if (adminAudit != null) adminAudit.record(access.accountId(), "DEPARTURE_CANCELLED", "DEPARTURE", sourceId,
                    "Chuyến " + Objects.toString(source.get("tour_code"), "#" + sourceId), "Hủy chuyến do sự cố",
                    Objects.toString(current.get("reason"), null), Map.of("status", "SUSPENDED"), Map.of("status", "CANCELLED"));
			jdbc.update("UPDATE departure_change SET status='COMPLETED',completed_at=? WHERE id=?",
					now(), rootId);
			return jdbc.queryForMap("SELECT * FROM departure_change WHERE id=?", rootId);
		});
	}

    private void requireUnstartedManifest(int departureId, boolean lock) {
        var started = jdbc.query("SELECT actual_departure_at,completed_at FROM departure_manifest WHERE departure_id=?"
                        + (lock ? " FOR UPDATE" : ""),
                (rs, index) -> rs.getTimestamp(1) != null || rs.getTimestamp(2) != null, departureId);
        require(!started.contains(true), "Chuyến đã xác nhận xuất phát hoặc đã chốt.");
    }

	/** @return the seats currently occupied on the source departure, already ledger-checked. */
	private int validateShop(ChangeRequests.Shop input, Map<String, Object> source, boolean preview) {
		requireClientKey(input.clientKey());
		normalizeNote(input.reason());
		normalizeNote(input.evidence());
		require(input.reason().trim().length() >= 20 && input.evidence().trim().length() >= 10,
				"Cần mô tả và bằng chứng cụ thể cho sự cố");
		require(SHOP_INCIDENT_REASONS.contains(input.reasonCode()),
				"Chỉ áp dụng sự cố bắt buộc, không dùng lý do thương mại");
		require(input.originalCannotDepart(),
				"Cần xác nhận chuyến cũ không thể khởi hành và chịu trách nhiệm về bằng chứng");
        require("OPEN".equals(source.get("operational_status")), "Chuyến cũ không còn mở hoặc đang xử lý sự cố.");
		require(v(source) == input.expectedVersion(), "Sức chứa/lịch đã thay đổi; tải lại trước khi xác nhận");
		require(input.departureAt() != null && input.returnAt() != null
						&& input.departureAt().isAfter(now()) && input.returnAt().isAfter(input.departureAt()),
				"Ngày mới không hợp lệ");
		require(Duration.between(input.departureAt(), input.returnAt())
						.equals(Duration.between(date(source.get("departure_date")),
								date(source.get("return_date")))),
				"Giữ nguyên thời lượng chuyến đi");
		require(!input.departureAt().equals(date(source.get("departure_date"))), "Ngày mới phải khác lịch cũ");
		require(input.responseDeadline() != null && input.responseDeadline().isAfter(now())
						&& input.responseDeadline().isBefore(input.departureAt())
                        && (!date(source.get("departure_date")).isAfter(now())
                            || input.responseDeadline().isBefore(date(source.get("departure_date")))),
				"Hạn phản hồi phải trước giờ khởi hành của các chuyến sắp tới.");

		int occupied = ledger.assertLedger(n(source, "id"));
		int seats = 0;
		var bookings = jdbc.queryForList(
				"SELECT * FROM booking WHERE tour_departure_id=? AND status='CONFIRMED' ORDER BY id",
				input.sourceId());
		for (var booking : bookings) {
			if (!preview) {
				ledger.lockBooking(n(booking, "id"), input.sourceId());
			}
			ledger.clearTransactions(n(booking, "id"));
			if (ledger.hasPaid(n(booking, "id"))) {
				ledger.paid(n(booking, "id"), n(booking, "account_id"));
			}
			seats += ledger.people(n(booking, "id"));
		}
		require(input.capacity() >= Math.max(1, seats) && input.capacity() <= 32767,
				"Lịch thay thế phải đủ sức chứa cho toàn bộ booking đang có");
		return occupied;
	}

	private void apply(Map<String, Object> change, Map<String, Object> booking,
					   Map<String, Object> source, Map<String, Object> target) {
		requireActiveBooking(booking);
		int bookingId = n(booking, "id");
		boolean shop = "SHOP".equals(change.get("kind"));
		require(shop
						? "ACCEPTED".equals(change.get("status")) && change.get("consent_source") != null
						: "PENDING".equals(change.get("status")),
				"Chưa có xác nhận hợp lệ để chuyển lịch");
		if (shop) {
			var root = jdbc.queryForMap(
					"SELECT * FROM departure_change WHERE id=?", change.get("shop_change_id"));
			require("OPEN".equals(root.get("status"))
							&& "SUSPENDED".equals(source.get("operational_status")),
					"Sự cố không còn mở");
			require((n(target, "id") == n(root, "target_departure_id")
							&& "REPLACEMENT_ONLY".equals(target.get("operational_status")))
							|| "OPEN".equals(target.get("operational_status")),
					"Lịch thay thế không còn phục vụ");
		} else {
            require(date(source.get("departure_date")).isAfter(now()),
                    "Chuyến cũ đã đến giờ đi; cần hỗ trợ thủ công");
			require("OPEN".equals(source.get("operational_status"))
							&& "OPEN".equals(target.get("operational_status")),
					"Lịch đang có sự cố");
			require(!date(change.get("created_at"))
							.isAfter(date(change.get("source_departure_at")).minusHours(CUSTOMER_CUTOFF_HOURS)),
					"Yêu cầu không đủ điều kiện 72 giờ");
		}

		ledger.clearTransactions(bookingId);
		snapshots(change, source, target);
		ledger.compatible(n(source, "id"), n(target, "id"), now());
		ledger.assertLedger(n(source, "id"));
		ledger.assertLedger(n(target, "id"));

		boolean paid = ledger.hasPaid(bookingId);
		if (paid) {
			var amount = ledger.paid(bookingId, n(booking, "account_id"));
			if (!shop) {
				ledger.samePrice(bookingId, n(source, "id"), amount);
			}
		} else {
			require(shop, "Chỉ khách đã thanh toán được chủ động yêu cầu dời");
		}

		var hold = ledger.reservation(bookingId);
		int seats = ledger.people(bookingId);
		int release = seatsToRelease(hold, paid, seats);
		ReservationPolicy.Plan plan = paid
				? null
				: policy.plan(date(booking.get("created_at")), date(target.get("departure_date")));
		if (plan != null) {
			require(!plan.immediate() && plan.deadline().isAfter(now()),
					"Hạn giữ chỗ gốc không còn phù hợp; cần hỗ trợ, không tự gia hạn từ ngày chuyển");
		}
		require(n(target, "available_seats") >= seats,
				"Lịch mới không còn đủ chỗ; booking vẫn ở lịch cũ");

		if (release > 0) {
			releaseSeats(source, release, bookingId);
		}
		tourClient.holdSeats(n(target, "id"), new TourInternalClient.HoldSeatsRequest(
				seats, String.valueOf(bookingId), paid ? now() : plan.deadline()));
		if (paid) tourClient.confirmHold(n(target, "id"), bookingId);
		jdbc.update("UPDATE booking SET tour_departure_id=? WHERE id=?", n(target, "id"), bookingId);

		if (hold == null) {
			jdbc.update("""
					INSERT INTO booking_reservation(booking_id,departure_id,departure_at,seats,status,
					  expires_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)
					""", bookingId, n(target, "id"), target.get("departure_date"), seats,
					paid ? "PAID" : "HELD", paid ? now() : plan.deadline(),
					booking.get("created_at"), now());
		} else {
			jdbc.update("""
					UPDATE booking_reservation SET departure_id=?,departure_at=?,status=?,expires_at=?,
					  reminder_one_at=?,reminder_two_at=?,cancel_reason=NULL,updated_at=?
					WHERE booking_id=?
					""", n(target, "id"), target.get("departure_date"), paid ? "PAID" : "HELD",
					paid ? hold.get("expires_at") : plan.deadline(),
					paid ? null : plan.reminderOne(),
					paid ? null : plan.reminderTwo(), now(), bookingId);
		}
		if (hold == null && !paid) {
			jdbc.update("""
					UPDATE booking_reservation SET reminder_one_at=?,reminder_two_at=? WHERE booking_id=?
					""", plan.reminderOne(), plan.reminderTwo(), bookingId);
		}

		jdbc.update("UPDATE booking_change SET applied_at=? WHERE id=?", now(), change.get("id"));
		state(((Number) change.get("id")).longValue(), "APPROVED", false,
				"Đã chuyển toàn bộ booking; giữ nguyên ngày đặt và chứng từ thanh toán");
		ledger.assertLedger(n(source, "id"));
		ledger.assertLedger(n(target, "id"));
	}

	private void releaseAndCancel(Map<String, Object> booking, Map<String, Object> source, String reason, boolean refundRecorded) {
		int bookingId = n(booking, "id");
		var hold = ledger.reservation(bookingId);
		int release;
		if (hold == null) {
			release = refundRecorded || ledger.hasPaid(bookingId) ? ledger.people(bookingId) : 0;
		} else {
			release = OCCUPYING_HOLD_STATUSES.contains(hold.get("status")) ? n(hold, "seats") : 0;
		}
		if (release > 0) {
			releaseSeats(source, release, bookingId);
		}
		jdbc.update("UPDATE booking SET status='CANCELLED' WHERE id=?", n(booking, "id"));
		jdbc.update("""
				UPDATE booking_reservation SET status='CANCELLED',cancel_reason=?,updated_at=?
				WHERE booking_id=?
				""", reason, now(), n(booking, "id"));
	}

	private int seatsToRelease(Map<String, Object> hold, boolean paid, int seats) {
		if (hold == null) {
			return paid ? seats : 0;
		}
		return OCCUPYING_HOLD_STATUSES.contains(hold.get("status")) ? n(hold, "seats") : 0;
	}

	private void releaseSeats(Map<String, Object> departure, int seats, int bookingId) {
		tourClient.releaseHold(n(departure, "id"), seats, bookingId);
	}

	private LocalDateTime now() {
		return LocalDateTime.now(clock).withNano(0);
	}

	private void requireOwner(int owner) {
		if (access.accountId() != owner) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chỉ chủ booking được xác nhận");
		}
	}

	private void checkPermission(String... names) {
		access.accountId();
		if (access.admin()) {
			return;
		}
		for (String name : names) {
			if (!CHANGE_PERMISSIONS.contains(name)) {
				throw new IllegalArgumentException("Unknown change permission");
			}
		}
		if (Arrays.stream(names).noneMatch(access::can)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN,
					"Bạn chưa được cấp quyền xử lý dời lịch");
		}
	}

	private static void requireClientKey(String value) {
		try {
			require(UUID.fromString(value).toString().equals(value), "Mã yêu cầu không hợp lệ");
		} catch (RuntimeException exception) {
			throw new IllegalArgumentException("Mã yêu cầu phải là UUID");
		}
	}

	private static String normalizeNote(String value) {
		require(value != null && !value.isBlank() && value.length() <= 1000,
				"Cần ghi rõ nội dung xử lý (tối đa 1000 ký tự)");
		return value.trim();
	}

	private Map<String, Object> findChange(long id) {
		var rows = jdbc.queryForList("SELECT * FROM booking_change WHERE id=?", id);
		requireFound(rows.size() == 1, "Không tìm thấy yêu cầu dời lịch");
		return rows.getFirst();
	}

	private Map<String, Object> findDepartureChange(long id) {
		var rows = jdbc.queryForList("SELECT * FROM departure_change WHERE id=?", id);
		requireFound(rows.size() == 1, "Không tìm thấy sự cố shop ghi nhận");
		return rows.getFirst();
	}

	private Map<String, Object> lockedChange(long id, long version) {
		var row = jdbc.queryForMap("SELECT * FROM booking_change WHERE id=? FOR UPDATE", id);
		require(v(row) == version, "Yêu cầu đã thay đổi; tải lại dữ liệu");
		return row;
	}

	private void audit(long id, String action, String note) {
		Integer actor = access.currentAccountId();
		if (actor == null) {
			// The deadline sweep runs without a request context; attribute the automatic
			// decision to the customer who filed the request rather than failing the row.
			actor = Objects.requireNonNull(jdbc.queryForObject(
					"SELECT requested_by FROM booking_change WHERE id=?", Integer.class, id));
		}
		jdbc.update("""
				INSERT INTO booking_change_event(booking_change_id,actor_id,action,note,created_at)
				VALUES(?,?,?,?,?)
				""", id, actor, action, note, now());
	}

	private void state(long id, String status, boolean active, String note) {
		jdbc.update("""
				UPDATE booking_change SET status=?,active_key=?,version=version+1,updated_at=? WHERE id=?
				""", status, active ? 1 : null, now(), id);
		audit(id, status, note);
		mail.enqueue(findChange(id));
	}

	private long insert(String sql, Object... args) {
		var keys = new GeneratedKeyHolder();
		jdbc.update(connection -> {
			var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
			for (int index = 0; index < args.length; index++) {
				statement.setObject(index + 1, args[index]);
			}
			return statement;
		}, keys);
		return Objects.requireNonNull(keys.getKey()).longValue();
	}

	private long insertChange(String clientKey, int booking, int account, String kind, Long root,
							  Map<String, Object> source, Map<String, Object> target, String reason) {
		long id = insert("""
				INSERT INTO booking_change(client_key,booking_id,account_id,kind,shop_change_id,
				  source_departure_id,target_departure_id,source_departure_at,source_return_at,
				  target_departure_at,target_return_at,status,reason,requested_by,created_at,updated_at)
				VALUES(?,?,?,?,?,?,?,?,?,?,?,'PENDING',?,?,?,?)
				""", clientKey, booking, account, kind, root, n(source, "id"), n(target, "id"),
				source.get("departure_date"), source.get("return_date"),
				target.get("departure_date"), target.get("return_date"),
				reason, access.accountId(), now(), now());
		audit(id, "PENDING", reason);
		mail.enqueue(findChange(id));
		return id;
	}

	private void requireActiveBooking(Map<String, Object> booking) {
		require("CONFIRMED".equals(booking.get("status")), "Booking đã hủy hoặc hết hạn");
	}

	private void snapshots(Map<String, Object> change, Map<String, Object> source,
						   Map<String, Object> target) {
		require(date(change.get("source_departure_at")).equals(date(source.get("departure_date")))
						&& date(change.get("source_return_at")).equals(date(source.get("return_date")))
						&& date(change.get("target_departure_at")).equals(date(target.get("departure_date")))
						&& date(change.get("target_return_at")).equals(date(target.get("return_date"))),
				"Lịch thay đổi so với nội dung đã xác nhận");
	}

	private int countActiveChanges(int booking) {
		return jdbc.queryForObject(
				"SELECT COUNT(*) FROM booking_change WHERE booking_id=? AND active_key=1",
				Integer.class, booking);
	}

	/** Rejects customer reschedule requests the shop left unanswered past the 72-hour cutoff. */
	public void rejectOverdueCustomerRequests() {
		var due = jdbc.queryForList("""
				SELECT id FROM booking_change
				WHERE kind='CUSTOMER' AND active_key=1 AND status='PENDING' AND source_departure_at<=?
				ORDER BY id LIMIT 100
				""", now().plusHours(CUSTOMER_CUTOFF_HOURS));
		for (var row : due) {
			try {
				tx.executeWithoutResult(status -> {
					long id = ((Number) row.get("id")).longValue();
					var change = jdbc.queryForMap("SELECT * FROM booking_change WHERE id=? FOR UPDATE", id);
					if (!"CUSTOMER".equals(change.get("kind")) || !"PENDING".equals(change.get("status"))
							|| change.get("active_key") == null || now().isBefore(customerDeadline(change))) return;
					state(id, "REJECTED", false, OVERDUE_NOTE);
				});
			} catch (RuntimeException busy) {
				// Another request is updating this row; the next sweep retries.
			}
		}
	}

	private void rejectOverdueCustomerChanges(int booking) {
		var due = jdbc.queryForList("""
				SELECT id FROM booking_change
				WHERE booking_id=? AND kind='CUSTOMER' AND active_key=1 AND status='PENDING'
				  AND source_departure_at<=?
				ORDER BY id
				""", booking, now().plusHours(CUSTOMER_CUTOFF_HOURS));
		for (var row : due) {
			long id = ((Number) row.get("id")).longValue();
			var change = jdbc.queryForMap("SELECT * FROM booking_change WHERE id=? FOR UPDATE", id);
			if (!"PENDING".equals(change.get("status")) || change.get("active_key") == null
					|| now().isBefore(customerDeadline(change))) continue;
			state(id, "REJECTED", false, OVERDUE_NOTE);
		}
	}

	private static LocalDateTime customerDeadline(Map<String, Object> change) {
		return date(change.get("source_departure_at")).minusHours(CUSTOMER_CUTOFF_HOURS);
	}
}