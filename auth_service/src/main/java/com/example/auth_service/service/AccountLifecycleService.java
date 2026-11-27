package com.example.auth_service.service;

import com.example.auth_service.config.AccountAccess;
import com.example.auth_service.entity.*;
import com.example.auth_service.repository.*;
import com.example.auth_service.repository.httpclient.BookingInternalClient;
import com.example.auth_service.repository.httpclient.InvoiceInternalClient;
import com.example.auth_service.repository.httpclient.NotificationInternalClient;
import com.example.auth_service.repository.httpclient.PaymentInternalClient;
import com.example.auth_service.repository.httpclient.TourInternalClient;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service @RequiredArgsConstructor
public class AccountLifecycleService {
    private final com.example.auth_service.operations.AdminAuditEvents adminAudit;
	private final UserRepository users;
	private final UserAccountEventRepository events;
	private final JdbcTemplate jdbc;
	private final AccountAccess access;
	private final AdminContinuity adminContinuity;
	private final TourInternalClient tourClient;
	private final PaymentInternalClient paymentClient;
	private final BookingInternalClient bookingClient;
	private final InvoiceInternalClient invoiceClient;
	private final NotificationInternalClient notificationClient;
	@Value("${app.registration.customer-role-name:Khách Hàng}") private String customerRoleName;
	private ResponseStatusException conflict(String text){return new ResponseStatusException(HttpStatus.CONFLICT,text);}
	private void require(boolean condition,String message){if(!condition)throw conflict(message);}
	private String reason(String text){
		if(text==null || text.isBlank() || text.length()>1000 || text.codePoints().anyMatch(c->Character.isISOControl(c) && c!='\n' && c!='\r' && c!='\t'))
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Cần lý do hợp lệ, tối đa 1000 ký tự");
		return text.trim();
	}
	private long actorId(){
		var auth=SecurityContextHolder.getContext().getAuthentication();
		try {if(auth==null || !auth.isAuthenticated())throw new IllegalArgumentException();return Long.parseLong(auth.getName());}
		catch(RuntimeException invalid){throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Cần đăng nhập quản trị");}
	}
	private User target(long id,String permissionCode){
		// Serialize administrator lifecycle changes, then lock the target account.
		adminContinuity.lockRoles();
		long actor=actorId();
		if(actor==id)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Không được tự khóa, lưu trữ hoặc xóa tài khoản đang sử dụng");
		var current=users.findById(actor).orElseThrow(()->new ResponseStatusException(HttpStatus.FORBIDDEN,"Tài khoản quản trị không còn tồn tại"));
		adminContinuity.refresh(current);
		if(current.getStatus()!=UserStatus.ACTIVE || !access.can(permissionCode))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Không có quyền quản lý trạng thái tài khoản");
		var target=users.lockForLifecycle(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy tài khoản"));
		adminContinuity.refresh(target);
		boolean admin=current.getRole()!=null && "admin".equalsIgnoreCase(current.getRole().getName());
		if(!admin && (target.getRole()==null || !customerRoleName.equalsIgnoreCase(target.getRole().getName())))
			throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Nhân viên chỉ được quản lý trạng thái tài khoản khách hàng");
		adminContinuity.requireUserRoleChange(id,null);
		return target;
	}
	private static long toLong(Object value) {
		if (value instanceof Number number) return number.longValue();
		if (value instanceof String text && !text.isBlank()) return Long.parseLong(text.trim());
		return 0L;
	}
	private static boolean toBoolean(Map<String, Object> body, String key) {
		if (body == null) return false;
		Object value = body.get(key);
		if (value instanceof Boolean flag) return flag;
		if (value instanceof Number number) return number.longValue() > 0;
		return value != null && Boolean.parseBoolean(value.toString());
	}
	private static LocalDateTime toLocalDateTime(Object value) {
		if (value instanceof LocalDateTime ldt) return ldt;
		if (value instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
		if (value instanceof String text && !text.isBlank()) return LocalDateTime.parse(text.trim());
		return null;
	}
	/** One clock for this service: the app writes Asia/Ho_Chi_Minh timestamps, so it must read them with the same clock. */
	private static LocalDateTime now(){return LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh"));}
	private void resolved(long id){
		LocalDateTime current=now();

		// 1. Check active bookings via Booking internal API (fail-closed)
		List<Map<String, Object>> activeBookings;
		try {
			activeBookings = bookingClient.getActiveBookings(id);
		} catch (Exception e) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Không thể xác minh trạng thái đặt chỗ từ booking-service");
		}
		boolean hasActiveBooking = false;
		if (activeBookings != null) {
			for (var booking : activeBookings) {
				LocalDateTime returnDt = toLocalDateTime(booking.get("return_date"));
				if (returnDt == null) {
					Object rawDepId = booking.get("tour_departure_id");
					Integer departureId = rawDepId instanceof Number n ? n.intValue() : null;
					if (departureId == null) {
						hasActiveBooking = true;
						break;
					}
					try {
						Map<String, Object> departure = tourClient.getDeparture(departureId);
						returnDt = departure != null ? toLocalDateTime(departure.get("return_date")) : null;
					} catch (Exception e) {
						hasActiveBooking = true;
						break;
					}
				}
				if (returnDt == null || returnDt.isAfter(current)) {
					hasActiveBooking = true;
					break;
				}
			}
		}
		require(!hasActiveBooking, "Tài khoản còn chuyến đi chưa hoàn tất");

		// 2. Check pending VNPay/MoMo transactions via Payment internal API (fail-closed)
		try {
			Map<String, Object> pending = paymentClient.getPendingTransactions(id);
			if (pending != null) {
				require(toLong(pending.get("vnpayPending")) == 0, "Còn giao dịch VNPay chưa có kết quả cuối cùng");
				require(toLong(pending.get("momoPending")) == 0, "Còn giao dịch MoMo chưa có kết quả cuối cùng");
			}
		} catch (ResponseStatusException rse) {
			throw rse;
		} catch (Exception e) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Không thể xác minh giao dịch đang chờ từ payment-service");
		}

		// 3. Check open payment reconciliations via Payment internal API (fail-closed)
		try {
			Map<String, Object> result = paymentClient.getOpenReconciliationCount(id);
			if (result != null) {
				require(toLong(result.get("count")) == 0, "Còn giao dịch cần đối soát");
			}
		} catch (ResponseStatusException rse) {
			throw rse;
		} catch (Exception e) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Không thể xác minh đối soát từ payment-service");
		}

		// 4. Check pending booking changes via Booking internal API (fail-closed)
		try {
			List<Map<String, Object>> pendingChanges = bookingClient.getPendingChanges(id);
			require(pendingChanges == null || pendingChanges.isEmpty(), "Còn yêu cầu dời lịch hoặc hoàn tiền chưa giải quyết");
		} catch (ResponseStatusException rse) {
			throw rse;
		} catch (Exception e) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Không thể xác minh yêu cầu thay đổi đặt chỗ từ booking-service");
		}
	}
	private boolean hasHistory(long id){
		try {
			if (toBoolean(bookingClient.hasUserHistory(id), "hasHistory")) return true;
			if (toBoolean(paymentClient.hasUserHistory(id), "hasHistory")) return true;
			if (toBoolean(invoiceClient.hasUserHistory(id), "hasHistory")) return true;
			if (toBoolean(tourClient.hasUserHistory(id), "hasHistory")) return true;
			if (toBoolean(notificationClient.hasUserHistory(id), "hasHistory")) return true;
			return false;
		} catch (Exception e) {
			// Fail-closed: if any owning service is unavailable, do not physically delete the account.
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Không thể xác minh lịch sử nghiệp vụ của tài khoản");
		}
	}
	private void audit(User target,String action,UserStatus next,String text){
		var event=new UserAccountEvent();event.setTargetUserId(target.getId());event.setActorId(actorId());event.setAction(action);
		event.setPreviousStatus(target.getStatus().name());event.setNewStatus(next==null?null:next.name());event.setReason(text);
		event.setCreatedAt(now());events.save(event);
        if (adminAudit != null) {
            String summary = switch (action) {
                case "LOCK" -> "Khóa tài khoản";
                case "UNLOCK" -> "Mở khóa tài khoản";
                case "ARCHIVE" -> "Lưu trữ tài khoản";
                default -> "Xóa tài khoản";
            };
            adminAudit.record("ACCOUNT_" + action, "ACCOUNT", "ACCOUNT", target.getId(),
                    "Tài khoản #" + target.getId(), summary, text,
                    Map.of("status", target.getStatus().name()),
                    Map.of("status", next == null ? "DELETED" : next.name()));
        }
	}
	@Transactional(isolation=Isolation.READ_COMMITTED)
	public User status(long id,UserStatus next,String why){
		String text=reason(why);
		if(next!=UserStatus.ACTIVE && next!=UserStatus.LOCKED)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Dùng thao tác lưu trữ riêng");
		var target=target(id,"USER_STATUS_UPDATE");
		require(target.getStatus()!=UserStatus.ARCHIVED,"Tài khoản đã lưu trữ không được mở lại");
		require(target.getStatus()!=next,"Tài khoản đã ở trạng thái này; hãy tải lại danh sách");
		audit(target,next==UserStatus.LOCKED?"LOCK":"UNLOCK",next,text);
		target.setStatus(next);return users.save(target);
	}
	@Transactional(isolation=Isolation.READ_COMMITTED)
	public User archive(long id,String why){
		String text=reason(why);var target=target(id,"USER_ARCHIVE");
		require(target.getStatus()==UserStatus.LOCKED,"Cần khóa tài khoản trước khi lưu trữ");resolved(id);
		audit(target,"ARCHIVE",UserStatus.ARCHIVED,text);target.setStatus(UserStatus.ARCHIVED);return users.save(target);
	}
	@Transactional(isolation=Isolation.READ_COMMITTED)
	public void delete(long id,String why){
		String text=reason(why);
		if(id<1)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Mã tài khoản không hợp lệ");
		adminContinuity.lockRoles();String owner=Long.toString(id);
		var target=target(id,"USERS_DELETE");
		require(target.getStatus()==UserStatus.LOCKED,"Chỉ xóa thật tài khoản đã khóa");
		require(!hasHistory(id),"Tài khoản đã có lịch sử nghiệp vụ; hãy lưu trữ thay vì xóa thật");
		resolved(id);audit(target,"DELETE",null,text);
		users.delete(target);
		writeAccountDeletedOutboxEvent(id, owner);
	}
	private void writeAccountDeletedOutboxEvent(long accountId, String ownerId) {
		var now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", LocalDateTime.class);
		String payload = "{\"accountId\":" + accountId + "}";
		jdbc.update("""
				INSERT INTO auth_outbox (
					event_id, event_type, aggregate_type, aggregate_id, aggregate_version,
					payload, status, attempts, available_at, created_at
				) VALUES (?, 'auth.account.deleted.v1', 'Account', ?, 1, ?, 'PENDING', 0, ?, ?)
				""", UUID.randomUUID().toString(), ownerId, payload, now, now);
	}
	public boolean manageable(User target) {
		try {
			long actor=actorId();if(actor==target.getId())return false;
			var current=users.findById(actor).orElse(null);
			return current!=null && current.getStatus()==UserStatus.ACTIVE && current.getRole()!=null &&
				("admin".equalsIgnoreCase(current.getRole().getName()) || target.getRole()!=null && customerRoleName.equalsIgnoreCase(target.getRole().getName()));
		} catch(ResponseStatusException invalid){return false;}
	}
	@Transactional(readOnly=true)
	public List<UserAccountEvent> history(long id){
		if(!access.can("USER_ACCOUNT_EVENTS"))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Không có quyền xem lịch sử trạng thái tài khoản");
		long actor=actorId();var current=users.findById(actor).orElseThrow(()->new ResponseStatusException(HttpStatus.FORBIDDEN,"Tài khoản quản trị không còn tồn tại"));
		var target=users.findById(id).orElse(null);
		boolean admin=current.getRole()!=null && "admin".equalsIgnoreCase(current.getRole().getName());
		if(!admin && (target==null || target.getRole()==null || !customerRoleName.equalsIgnoreCase(target.getRole().getName())))
			throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Nhân viên chỉ được xem lịch sử tài khoản khách hàng");
		return events.findByTargetUserIdOrderByIdDesc(id,org.springframework.data.domain.PageRequest.of(0,100));
	}
}
