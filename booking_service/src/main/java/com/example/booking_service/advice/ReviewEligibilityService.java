package com.example.booking_service.advice;

import com.example.booking_service.notification.ReminderStateReader;
import com.example.booking_service.reservation.BookingAccess;
import com.example.booking_service.repository.httpclient.AuthInternalClient;
import com.example.booking_service.repository.httpclient.InvoiceInternalClient;
import com.example.booking_service.repository.httpclient.PaymentInternalClient;
import com.example.booking_service.repository.httpclient.TourInternalClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class ReviewEligibilityService {
	public record Result(boolean eligible, String message) {}
	private final JdbcTemplate jdbc;
	private final BookingAccess access;
	private final TourInternalClient tourClient;
	private final AuthInternalClient authClient;
	private final PaymentInternalClient paymentClient;
	private final InvoiceInternalClient invoiceClient;

	public ReviewEligibilityService(JdbcTemplate jdbc, BookingAccess access,
			TourInternalClient tourClient, AuthInternalClient authClient,
			PaymentInternalClient paymentClient, InvoiceInternalClient invoiceClient) {
		this.jdbc=jdbc; this.access=access;
		this.tourClient=tourClient; this.authClient=authClient;
		this.paymentClient=paymentClient; this.invoiceClient=invoiceClient;
	}
	@Transactional(readOnly=true)
	public Result check(long tourId) {
		if(tourId<1) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"Mã tour không hợp lệ");
		// Always the authenticated owner, including admins. Never accept an accountId from the caller.
		var ids=jdbc.queryForList("""
			SELECT b.id FROM booking b
			JOIN departure_manifest m ON m.departure_id=b.tour_departure_id AND m.completed_at IS NOT NULL
			WHERE EXISTS (
				SELECT 1 FROM customer p JOIN departure_attendance a
				  ON a.customer_id=p.id AND a.departure_id=b.tour_departure_id AND a.status IN ('RETURNED','SEPARATED')
				WHERE p.booking_id=b.id
			)
			AND b.account_id=? AND b.status='CONFIRMED'
			  AND NOT EXISTS (SELECT 1 FROM booking_change c WHERE c.booking_id=b.id AND c.active_key=1)
			""", Integer.class, access.accountId());
		
		// Filter by tourId using Tour internal API
		List<Integer> filteredIds = new ArrayList<>();
		for (Integer bookingId : ids) {
			try {
				var booking = jdbc.queryForMap("SELECT tour_departure_id FROM booking WHERE id=?", bookingId);
				Integer departureId = (Integer) booking.get("tour_departure_id");
				if (departureId != null) {
					Map<String, Object> departure = tourClient.getDeparture(departureId);
					if (departure != null && ((Number) departure.get("tour_id")).longValue() == tourId) {
						filteredIds.add(bookingId);
					}
				}
			} catch (Exception ignored) {
				// Skip if tour service unavailable
			}
		}
		
		var reader=new ReminderStateReader(jdbc, authClient, tourClient, paymentClient, invoiceClient);
		for(int id:filteredIds) {
			var found=reader.read(id);
			if(found.isEmpty()) continue;
			var s=found.get();
			if(s.paymentCount()==1 && s.invoiceCount()==1 && (s.allPaidCount()==2 || s.allPaidCount()==3)
				&& s.paymentAmount()!=null && s.invoiceAmount()!=null && s.paymentAmount().signum()>0
				&& s.paymentAmount().compareTo(s.invoiceAmount())==0 && !s.reconciliation()
				&& s.passengers()>0 && s.reservationMatches()
				&& (s.reservationStatus()==null || "PAID".equals(s.reservationStatus())))
				return new Result(true,"Bạn có thể đánh giá tour đã hoàn tất; mỗi tài khoản một đánh giá cho mỗi tour.");
		}
		return new Result(false,"Chỉ tài khoản có booking đã thanh toán được xác minh và có hành khách được xác nhận trở về sau khi điều hành chốt chuyến mới được đánh giá tour này.");
	}
}