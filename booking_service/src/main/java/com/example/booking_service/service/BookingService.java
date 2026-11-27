package com.example.booking_service.service;

import com.example.booking_service.dto.request.BookingRequest;
import com.example.booking_service.dto.response.BookingResponse;
import com.example.booking_service.exception.AppException;
import com.example.booking_service.exception.ErrorCode;
import com.example.booking_service.reservation.BookingLifecycleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BookingService {
    private final BookingLifecycleService lifecycle;
    public List<BookingResponse> getAllBookings(Integer page, Integer size, boolean mine) {
        return lifecycle.list(page, size, mine);
    }
    public List<BookingResponse> getAllBookings(Integer page, Integer size, boolean mine, Integer departureId) {
        return lifecycle.list(page, size, mine, departureId);
    }
    public BookingResponse getBookingById(Long id) { return lifecycle.get(requireId(id)); }
    public BookingResponse createBooking(BookingRequest request) { return lifecycle.create(request); }
    public BookingResponse findOwnAttempt(String key) { return lifecycle.findOwnAttempt(key); }
    public BookingLifecycleService.HistoryPage ownHistory(int page, Long bookingId, String status,
                                                        java.time.LocalDate from, java.time.LocalDate to) {
        return lifecycle.ownHistory(page, bookingId, status, from, to);
    }
    /** Compatibility route: cancellation preserves history and allows a new booking. */
    public BookingResponse deleteBooking(Long id) { return lifecycle.cancel(requireId(id)); }

    /** IDs outside the int range cannot exist; treat them as missing instead of overflowing into a 500. */
    private static int requireId(Long id) {
        if (id == null || id < 1 || id > Integer.MAX_VALUE) throw new AppException(ErrorCode.BOOKING_NOT_EXISTED);
        return id.intValue();
    }
}
