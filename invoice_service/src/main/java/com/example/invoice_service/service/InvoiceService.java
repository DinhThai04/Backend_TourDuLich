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
import com.example.invoice_service.repository.httpclient.BookingInternalClient;
import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
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
import java.util.Map;

@Service
@Slf4j
public class InvoiceService {

    // Shared money convention: 1 = VNPay, 2 = MoMo, 3 = cash desk.
    private static final int CASH_PAYMENT_METHOD_ID = 3;
    private static final String INVOICE_CREATED_MESSAGE = "Hoá đơn được tạo thành công";
    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final InvoiceRepository invoiceRepository;
    private final InvoiceMapper invoiceMapper;
    private final TourDepartureClient tourDepartureClient;
    private final BookingInternalClient bookingClient;
    private final org.springframework.transaction.support.TransactionTemplate transactions;

    public InvoiceService(InvoiceRepository invoiceRepository, InvoiceMapper invoiceMapper,
            TourDepartureClient tourDepartureClient, BookingInternalClient bookingClient,
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.invoiceRepository = invoiceRepository;
        this.invoiceMapper = invoiceMapper;
        this.tourDepartureClient = tourDepartureClient;
        this.bookingClient = bookingClient;
        this.transactions = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
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

    private static int recordId(Long id) {
        if (id == null || id < 1 || id > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy hóa đơn");
        }
        return id.intValue();
    }

    public InvoiceResponse createInvoice(InvoiceRequest invoiceRequest) {
        try {
            int bId = bookingId(invoiceRequest);
            if (hasReservation(bId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Booking giữ chỗ cần thanh toán qua VNPay; không tạo hóa đơn bằng luồng cũ");
            }
            var owner = findBookingOwner(bId);
            InvoiceAccess.requireCreateOwner(owner);
            if (!String.valueOf(owner).equals(invoiceRequest.getBookingRequest().getUserId())) {
                throw new AccessDeniedException("Thông tin tài khoản không khớp booking");
            }

            ApiResponse<TourDepartureResponse> departureResponse = tourDepartureClient.getTourDepartureById(
                    departureId(invoiceRequest));
            if (departureResponse == null || departureResponse.getResult() == null) {
                throw new AppException(ErrorCode.TOUR_DEPARTURE_NOT_EXISTED);
            }

            verifyLegacyBooking(invoiceRequest, bId);
            long totalExpense = calculateTotalTourExpense(bId, departureResponse.getResult());

            return transactions.execute(status -> {
                if (invoiceRepository.findByBookingId(bId).filter(i -> InvoiceStatus.PAID.equals(i.getStatus())).isPresent()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Booking đã có hóa đơn PAID");
                }
                Invoice invoice = invoiceMapper.toInvoice(invoiceRequest);
                invoice.setAccountId(owner);
                invoice.setBookingId(bId);
                invoice.setStatus(InvoiceStatus.PAID);
                invoice.setDayOfPay(LocalDateTime.now(VIETNAM_ZONE));
                invoice.setPaymentMethodId(CASH_PAYMENT_METHOD_ID);
                invoice.setTotalBookingTourExpense(BigDecimal.valueOf(totalExpense));
                invoiceRepository.saveAndFlush(invoice);
                decrementSeats(bId, invoiceRequest);
                return buildInvoiceResponse(invoice, totalExpense);
            });
        } catch (AppException | AccessDeniedException | ResponseStatusException exception) {
            throw exception;
        } catch (IllegalArgumentException invalidRequest) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalidRequest.getMessage(), invalidRequest);
        } catch (FeignException.NotFound exception) {
            throw new AppException(ErrorCode.RESOURCE_NOT_FOUND);
        } catch (FeignException exception) {
            log.error("Không gọi được service nội bộ khi tạo hóa đơn: {}", exception.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Không lấy được thông tin từ dịch vụ liên quan; vui lòng thử lại sau", exception);
        } catch (DataIntegrityViolationException exception) {
            throw new AppException(ErrorCode.DATA_INTEGRITY_ERROR);
        } catch (Exception exception) {
            log.error("Lỗi khi tạo hóa đơn", exception);
            throw new AppException(ErrorCode.UNCATEGORIZED_EXCEPTION);
        }
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

    private boolean hasReservation(int bookingId) {
        try {
            Map<String, Object> reservation = bookingClient.getBookingReservation(bookingId);
            return reservation != null && !reservation.isEmpty();
        } catch (FeignException.NotFound ignored) {
            return false;
        }
    }

    private Integer findBookingOwner(int bookingId) {
        try {
            Map<String, Object> owner = bookingClient.getBookingOwner(bookingId);
            if (owner == null || owner.get("account_id") == null) {
                throw new AppException(ErrorCode.BOOKING_NOT_EXISTED);
            }
            return ((Number) owner.get("account_id")).intValue();
        } catch (FeignException.NotFound ignored) {
            throw new AppException(ErrorCode.BOOKING_NOT_EXISTED);
        }
    }

    private long calculateTotalTourExpense(int bookingId, TourDepartureResponse tourDeparture) {
        try {
            Map<String, Object> snapshot = bookingClient.getBookingPriceSnapshot(bookingId);
            if (snapshot != null && snapshot.get("total_amount") != null) {
                long amount = new BigDecimal(snapshot.get("total_amount").toString()).longValueExact();
                if (amount <= 0) throw new IllegalArgumentException("Số tiền snapshot không hợp lệ");
                return amount;
            }
        } catch (FeignException.NotFound ignored) {
            // Fall through to current tour price for legacy bookings without snapshot
        }

        var tourPrice = tourDeparture.getTourPrice();
        if (tourPrice == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Chưa lấy được bảng giá tour; vui lòng thử lại sau");
        }
        List<Map<String, Object>> customers = bookingClient.getBookingCustomers(bookingId);
        if (customers == null || customers.isEmpty()) throw new IllegalArgumentException("Booking chưa có hành khách");

        long adultCount = 0, childCount = 0, toddlerCount = 0, infantCount = 0;
        for (Map<String, Object> row : customers) {
            Object rawType = row.get("booking_type");
            if (rawType == null) throw new IllegalArgumentException("Loại hành khách không hợp lệ");
            switch (rawType.toString().toUpperCase(Locale.ROOT)) {
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

    private void decrementSeats(int bookingId, InvoiceRequest request) {
        List<Map<String, Object>> customers = bookingClient.getBookingCustomers(bookingId);
        int bookedSeats = customers != null ? customers.size() : 0;
        if (bookedSeats <= 0
                || request.getBookingRequest().getListOfCustomers() == null
                || bookedSeats != request.getBookingRequest().getListOfCustomers().size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Danh sách khách đã thay đổi, vui lòng tải lại");
        }
        try {
            bookingClient.decrementSeats(bookingId, new BookingInternalClient.DecrementSeatsRequest(bookedSeats));
        } catch (FeignException.Conflict conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Chuyến đi không còn đủ chỗ");
        }
    }

    private void verifyLegacyBooking(InvoiceRequest request, int bookingId) {
        Map<String, Object> booking;
        try {
            booking = bookingClient.getBooking(bookingId);
        } catch (FeignException.NotFound ignored) {
            throw new AppException(ErrorCode.BOOKING_NOT_EXISTED);
        }
        if (booking == null || booking.isEmpty()) {
            throw new AppException(ErrorCode.BOOKING_NOT_EXISTED);
        }
        int departureId = ((Number) booking.get("tour_departure_id")).intValue();
        if (!String.valueOf(departureId).equals(request.getBookingRequest().getTourDepartureId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Lịch booking không khớp yêu cầu");
        }
        if (!"CONFIRMED".equals(booking.get("status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Booking đã hủy");
        }
        if (List.of("SUSPENDED", "CANCELLED").contains(String.valueOf(booking.get("operational_status")))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Chuyến đang dừng do sự cố");
        }
        Object departureTime = booking.get("departure_date");
        if (departureTime != null) {
            LocalDateTime start = departureTime instanceof LocalDateTime time
                    ? time
                    : departureTime instanceof Timestamp ts ? ts.toLocalDateTime() : LocalDateTime.parse(departureTime.toString());
            if (!start.isAfter(LocalDateTime.now(VIETNAM_ZONE))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Chuyến đi đã khởi hành");
            }
        }
        Map<String, Object> evidence = bookingClient.getPaymentEvidence(bookingId);
        int evidenceCount = evidence != null && evidence.get("evidence_count") instanceof Number n ? n.intValue() : 0;
        if (evidenceCount > 0) {
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
