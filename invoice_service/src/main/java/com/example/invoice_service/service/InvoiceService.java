package com.example.invoice_service.service;

import com.example.invoice_service.client.TourDepartureClient;
import com.example.invoice_service.config.InvoiceAccess;
import com.example.invoice_service.dto.request.InvoiceRequest;
import com.example.invoice_service.dto.response.ApiResponse;
import com.example.invoice_service.dto.response.InvoiceResponse;
import com.example.invoice_service.dto.response.TourDepartureResponse;
import com.example.invoice_service.entity.Invoice;
import com.example.invoice_service.entity.InvoiceStatus;
import com.example.invoice_service.exception.AppException;
import com.example.invoice_service.exception.ErrorCode;
import com.example.invoice_service.mapper.InvoiceMapper;
import com.example.invoice_service.paging.PageSlice;
import com.example.invoice_service.repository.InvoiceRepository;

import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

@Service
@Slf4j
public class InvoiceService {

    // Shared money convention: 1 = VNPay, 2 = MoMo, 3 = cash desk.
    private static final int CASH_PAYMENT_METHOD_ID = 3;
    private static final String INVOICE_CREATED_MESSAGE = "Hoá đơn được tạo thành công";
    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final JdbcTemplate jdbc;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceMapper invoiceMapper;
    private final TourDepartureClient tourDepartureClient;
    private final org.springframework.transaction.support.TransactionTemplate transactions;

    public InvoiceService(JdbcTemplate jdbc, InvoiceRepository invoiceRepository, InvoiceMapper invoiceMapper,
            TourDepartureClient tourDepartureClient,
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.invoiceRepository = invoiceRepository;
        this.invoiceMapper = invoiceMapper;
        this.tourDepartureClient = tourDepartureClient;
        this.transactions = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        // The seat/invoice decision is a read-after-lock: a waiter must see the invoice the
        // previous holder just committed, not a snapshot taken before it acquired the row.
        this.transactions.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** {@code mine} keeps the profile page on the caller's own rows even for a staff account. */
    public List<InvoiceResponse> getAllInvoices(Integer page, Integer size, boolean mine) {
        Pageable pageable = PageSlice.of(page, size).pageable(Sort.by(Sort.Direction.DESC, "id"));
        List<Invoice> invoices = !mine && InvoiceAccess.canReadAll()
                ? invoiceRepository.findAll(pageable).getContent()
                : invoiceRepository.findByAccountId(InvoiceAccess.accountId(), pageable).getContent();
        return invoiceMapper.toInvoiceResponseList(invoices);
    }

    public InvoiceResponse getInvoiceById(Long id) {
        Invoice invoice = invoiceRepository.findById(recordId(id))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy hóa đơn"));
        InvoiceAccess.requireReadOwner(invoice.getAccountId(), "INVOICES_GET_DETAIL");
        return invoiceMapper.toInvoiceResponse(invoice);
    }

    public InvoiceResponse getInvoiceByBookingId(Long bookingId) {
        Invoice invoice = invoiceRepository.findByBookingId(recordId(bookingId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy hóa đơn"));
        InvoiceAccess.requireReadOwner(invoice.getAccountId(), "INVOICES_GET_BY_BOOKING");
        return invoiceMapper.toInvoiceResponse(invoice);
    }

    /** Rejects ids that do not fit the integer primary key instead of silently wrapping them. */
    private static int recordId(Long id) {
        if (id == null || id < 1 || id > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy hóa đơn");
        }
        return id.intValue();
    }

    public InvoiceResponse createInvoice(InvoiceRequest invoiceRequest) {
        try {
            if (hasReservation(invoiceRequest)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Booking giữ chỗ cần thanh toán qua VNPay; không tạo hóa đơn bằng luồng cũ");
            }
            var owner = findBookingOwner(invoiceRequest);
            InvoiceAccess.requireCreateOwner(owner);
            if (!String.valueOf(owner).equals(invoiceRequest.getBookingRequest().getUserId())) {
                throw new AccessDeniedException("Thông tin tài khoản không khớp booking");
            }

            // Fetch remote data BEFORE opening the transaction, so no database row lock
            // is held while waiting on another service over HTTP.
            ApiResponse<TourDepartureResponse> departureResponse = tourDepartureClient.getTourDepartureById(
                    departureId(invoiceRequest));

            if (departureResponse == null || departureResponse.getResult() == null) {
                throw new AppException(ErrorCode.TOUR_DEPARTURE_NOT_EXISTED);
            }

            // Locks and writes are re-checked inside the transaction, because the remote
            // data above may be seconds old by the time the transaction starts.
            return transactions.execute(status -> persistPaidInvoice(
                    invoiceRequest, owner, departureResponse.getResult()));
        } catch (AppException | AccessDeniedException | ResponseStatusException exception) {
            throw exception;
        } catch (IllegalArgumentException invalidRequest) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalidRequest.getMessage(), invalidRequest);
        } catch (FeignException.NotFound exception) {
            throw new AppException(ErrorCode.RESOURCE_NOT_FOUND);
        } catch (FeignException exception) {
            // The Feign message embeds the response body; log only which dependency call failed.
            log.error("Không gọi được tour-service khi tạo hóa đơn: {}", exception.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Không lấy được lịch khởi hành; vui lòng thử lại sau", exception);
        } catch (DataIntegrityViolationException exception) {
            throw new AppException(ErrorCode.DATA_INTEGRITY_ERROR);
        } catch (Exception exception) {
            log.error("Lỗi khi tạo hóa đơn", exception);
            throw new AppException(ErrorCode.UNCATEGORIZED_EXCEPTION);
        }
    }

    private InvoiceResponse persistPaidInvoice(InvoiceRequest invoiceRequest, int accountId,
            TourDepartureResponse departure) {
        int bookingId = bookingId(invoiceRequest);
        lockLegacyBooking(invoiceRequest);

        long totalExpense = calculateTotalTourExpense(bookingId, departure);
        Invoice invoice = invoiceMapper.toInvoice(invoiceRequest);
        invoice.setAccountId(accountId);
        invoice.setBookingId(bookingId);
        invoice.setStatus(InvoiceStatus.PAID);
        invoice.setDayOfPay(LocalDateTime.now(VIETNAM_ZONE));
        invoice.setPaymentMethodId(CASH_PAYMENT_METHOD_ID);
        invoice.setTotalBookingTourExpense(BigDecimal.valueOf(totalExpense));

        updateAvailableSeats(departure, invoiceRequest);
        invoiceRepository.save(invoice);
        // Other services read the payment ledger, not the invoice: a paid cash-desk invoice must
        // leave the matching PAID payment row or refunds/reports treat the booking as unpaid.
        if (jdbc.queryForObject("SELECT COUNT(*) FROM payment WHERE booking_id=? AND status='PAID'",
                Integer.class, invoice.getBookingId()) == 0) {
            jdbc.update("""
                INSERT INTO payment (account_id,booking_id,day_of_pay,pay_method_id,status,total_booking_tour_expense)
                VALUES (?,?,?,?,'PAID',?)
                """, invoice.getAccountId(), invoice.getBookingId(), invoice.getDayOfPay(),
                    invoice.getPaymentMethodId(), BigDecimal.valueOf(totalExpense));
        }
        return buildInvoiceResponse(invoice, totalExpense);
    }

    public InvoiceResponse payAndCreateInvoice(InvoiceRequest invoiceRequest) {
        return createInvoice(invoiceRequest);
    }

    private static int bookingId(InvoiceRequest invoiceRequest) {
        try {
            return Integer.parseInt(invoiceRequest.getBookingRequest().getBookingId());
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Mã booking không hợp lệ");
        }
    }

    private static long departureId(InvoiceRequest invoiceRequest) {
        try {
            return Long.parseLong(invoiceRequest.getBookingRequest().getTourDepartureId());
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Mã lịch khởi hành không hợp lệ");
        }
    }

    private boolean hasReservation(InvoiceRequest invoiceRequest) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM booking_reservation WHERE booking_id=?",
                Integer.class, bookingId(invoiceRequest)) > 0;
    }

    private Integer findBookingOwner(InvoiceRequest invoiceRequest) {
        // Shared booking schema: the local row is authoritative and needs no caller-token Feign call.
        var owners = jdbc.queryForList(
                "SELECT account_id FROM booking WHERE id=?",
                Integer.class, bookingId(invoiceRequest));
        if (owners.isEmpty()) throw new AppException(ErrorCode.BOOKING_NOT_EXISTED);
        return owners.getFirst();
    }

    private long calculateTotalTourExpense(int bookingId, TourDepartureResponse tourDeparture) {
        var snapshots = jdbc.queryForList(
                "SELECT total_amount FROM booking_price_snapshot WHERE booking_id=?", bookingId);
        if (!snapshots.isEmpty()) {
            long amount = new BigDecimal(snapshots.getFirst().get("total_amount").toString()).longValueExact();
            if (amount <= 0) throw new IllegalArgumentException("Số tiền snapshot không hợp lệ");
            return amount;
        }
        // Legacy bookings created before the snapshot table still use the current tour price.
        // Pricing may be down or lack a record: report an unavailable service, never an NPE.
        var tourPrice = tourDeparture.getTourPrice();
        if (tourPrice == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Chưa lấy được bảng giá tour; vui lòng thử lại sau");
        }
        List<String> customerTypes = jdbc.queryForList(
                "SELECT booking_type FROM customer WHERE booking_id=? ORDER BY id", String.class, bookingId);
        if (customerTypes.isEmpty()) throw new IllegalArgumentException("Booking chưa có hành khách");

        long adultCount = 0;
        long childCount = 0;
        long toddlerCount = 0;
        long infantCount = 0;

        for (String bookingType : customerTypes) {
            if (bookingType == null) throw new IllegalArgumentException("Loại hành khách không hợp lệ");
            switch (bookingType.toUpperCase(Locale.ROOT)) {
                case "ADULT" -> adultCount++;
                case "CHILD" -> childCount++;
                case "TODDLER" -> toddlerCount++;
                case "INFANT" -> infantCount++;
                default -> throw new IllegalArgumentException("Loại hành khách không hợp lệ");
            }
        }

        return adultCount * vnd(tourPrice.getAdultPrice())
                + childCount * vnd(tourPrice.getChildPrice())
                + toddlerCount * vnd(tourPrice.getToddlerPrice())
                + infantCount * vnd(tourPrice.getInfantPrice());
    }

    private static long vnd(String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Bảng giá tour không đầy đủ; vui lòng thử lại sau");
        }
        return new BigDecimal(value).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private void updateAvailableSeats(TourDepartureResponse tour, InvoiceRequest request) {
        int bookedSeats = jdbc.queryForObject(
                "SELECT COUNT(*) FROM customer WHERE booking_id=?",
                Integer.class, bookingId(request));
        if (bookedSeats <= 0
                || request.getBookingRequest().getListOfCustomers() == null
                || bookedSeats != request.getBookingRequest().getListOfCustomers().size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Danh sách khách đã thay đổi, vui lòng tải lại");
        }
        if (jdbc.update("""
                UPDATE tour_departure SET available_seats=available_seats-?,version=version+1
                WHERE id=? AND available_seats>=?
                """, bookedSeats, Integer.parseInt(tour.getId()), bookedSeats) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Chuyến đi không còn đủ chỗ");
        }
    }

    private void lockLegacyBooking(InvoiceRequest request) {
        int bookingId = bookingId(request);
        var departureIds = jdbc.queryForList(
                "SELECT tour_departure_id FROM booking WHERE id=?", Integer.class, bookingId);
        if (departureIds.isEmpty()) throw new AppException(ErrorCode.BOOKING_NOT_EXISTED);
        int departureId = departureIds.getFirst();
        var schedules = jdbc.queryForList(
                "SELECT * FROM tour_departure WHERE id=? FOR UPDATE", departureId);
        if (schedules.isEmpty()) throw new AppException(ErrorCode.TOUR_DEPARTURE_NOT_EXISTED);
        var schedule = schedules.getFirst();
        if (List.of("SUSPENDED", "CANCELLED").contains(String.valueOf(schedule.get("operational_status")))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Chuyến đang dừng do sự cố");
        }

        var bookings = jdbc.queryForList("SELECT * FROM booking WHERE id=? FOR UPDATE", bookingId);
        if (bookings.isEmpty()) throw new AppException(ErrorCode.BOOKING_NOT_EXISTED);
        var booking = bookings.getFirst();
        if (departureId != ((Number) booking.get("tour_departure_id")).intValue()
                || !String.valueOf(departureId).equals(request.getBookingRequest().getTourDepartureId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Lịch booking không khớp yêu cầu");
        }
        if (!"CONFIRMED".equals(booking.get("status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Booking đã hủy");
        }
        // Re-check under the booking lock: a reservation may have been created after the
        // pre-transaction check, and this legacy path must never decrement seats twice.
        if (jdbc.queryForObject("SELECT COUNT(*) FROM booking_reservation WHERE booking_id=?",
                Integer.class, bookingId) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Booking giữ chỗ cần thanh toán qua VNPay; không tạo hóa đơn bằng luồng cũ");
        }

        var departureTime = schedule.get("departure_date");
        var start = departureTime instanceof LocalDateTime time
                ? time
                : ((Timestamp) departureTime).toLocalDateTime();
        LocalDateTime now = LocalDateTime.now(VIETNAM_ZONE);
        if (!start.isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Chuyến đi đã khởi hành");
        }

        int paymentEvidence = jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM invoice WHERE booking_id=? AND status='PAID')
                  +(SELECT COUNT(*) FROM payment WHERE booking_id=? AND status='PAID')
                  +(SELECT COUNT(*) FROM vnpay_transaction
                    WHERE booking_id=? AND (status='PAID' OR (status='PENDING' AND expires_at>?)))
                  +(SELECT COUNT(*) FROM momo_transaction
                    WHERE booking_id=? AND (status='PAID' OR (status='PENDING' AND expires_at>?)))
                  +(SELECT COUNT(*) FROM payment_reconciliation WHERE booking_id=? AND status='OPEN')
                """, Integer.class, bookingId, bookingId, bookingId, now, bookingId, now, bookingId);
        if (paymentEvidence > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Booking đã thanh toán hoặc đang có giao dịch cần xử lý; không trừ chỗ lần nữa");
        }
    }

    private InvoiceResponse buildInvoiceResponse(Invoice invoice, long total) {
        return InvoiceResponse.builder()
                .id(String.valueOf(invoice.getId()))
                .accountId(String.valueOf(invoice.getAccountId()))
                .bookingId(String.valueOf(invoice.getBookingId()))
                .paymentMethodId(String.valueOf(invoice.getPaymentMethodId()))
                .dayOfPay(String.valueOf(invoice.getDayOfPay()))
                .status(invoice.getStatus().name())
                .totalBookingTourExpense(String.valueOf(total))
                .message(INVOICE_CREATED_MESSAGE)
                .build();
    }
}
