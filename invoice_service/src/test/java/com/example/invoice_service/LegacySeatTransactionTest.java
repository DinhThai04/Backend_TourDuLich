package com.example.invoice_service;

import com.example.invoice_service.client.*;
import com.example.invoice_service.dto.request.*;
import com.example.invoice_service.dto.response.*;
import com.example.invoice_service.mapper.InvoiceMapper;
import com.example.invoice_service.repository.InvoiceRepository;
import com.example.invoice_service.service.InvoiceService;
import org.junit.jupiter.api.*;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.sql.init.mode=never","spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect","spring.jpa.show-sql=false"})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Import({InvoiceService.class,LegacySeatTransactionTest.MapperConfiguration.class})
class LegacySeatTransactionTest {
    @TestConfiguration
    static class MapperConfiguration {
        @Bean
        InvoiceMapper invoiceMapper() {
            return Mappers.getMapper(InvoiceMapper.class);
        }
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired InvoiceService service;
    @Autowired InvoiceRepository invoices;
    @MockBean TourDepartureClient departures;
    @MockBean com.example.invoice_service.repository.httpclient.BookingInternalClient bookingClient;
    InvoiceRequest request;
    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    @BeforeEach void seed() {
        for(String ddl:List.of(
          "CREATE TABLE IF NOT EXISTS tour_departure(id INT PRIMARY KEY,departure_date TIMESTAMP,available_seats INT,capacity INT,version BIGINT DEFAULT 0,operational_status VARCHAR(20) DEFAULT 'OPEN')",
          "CREATE TABLE IF NOT EXISTS booking(id INT PRIMARY KEY,account_id INT,tour_departure_id INT,status VARCHAR(20))",
          "CREATE TABLE IF NOT EXISTS customer(id INT PRIMARY KEY,booking_id INT,booking_type VARCHAR(20))",
          "CREATE TABLE IF NOT EXISTS booking_price_snapshot(booking_id INT PRIMARY KEY,total_amount DECIMAL(19,0))",
          "CREATE TABLE IF NOT EXISTS booking_reservation(booking_id INT PRIMARY KEY)",
          "CREATE TABLE IF NOT EXISTS payment(id INT AUTO_INCREMENT PRIMARY KEY,account_id INT,booking_id INT,day_of_pay TIMESTAMP,pay_method_id INT,status VARCHAR(20),total_booking_tour_expense DECIMAL(19,0))",
          "CREATE TABLE IF NOT EXISTS vnpay_transaction(txn_ref VARCHAR(32) PRIMARY KEY,booking_id INT,status VARCHAR(20),expires_at TIMESTAMP)",
          "CREATE TABLE IF NOT EXISTS momo_transaction(order_id VARCHAR(32) PRIMARY KEY,booking_id INT,status VARCHAR(20),expires_at TIMESTAMP)",
          "CREATE TABLE IF NOT EXISTS payment_reconciliation(txn_ref VARCHAR(32) PRIMARY KEY,booking_id INT,status VARCHAR(20))"))jdbc.execute(ddl);
        // The legacy Payment entity represents method metadata; production's shared payment table also has these columns.
        jdbc.execute("ALTER TABLE payment ADD COLUMN IF NOT EXISTS booking_id INT");
        jdbc.execute("ALTER TABLE payment ADD COLUMN IF NOT EXISTS status VARCHAR(20)");
        for(String table:List.of("invoice","payment","vnpay_transaction","momo_transaction","payment_reconciliation","booking_reservation","customer","booking","tour_departure"))jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO tour_departure(id,departure_date,available_seats,capacity,version) VALUES(1,?,10,10,0)",LocalDateTime.now(VIETNAM_ZONE).plusDays(30));
        jdbc.update("INSERT INTO booking VALUES(1,7,1,'CONFIRMED')");jdbc.update("INSERT INTO customer VALUES(1,1,'ADULT')");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("7","",List.of()));
        var customer=new CustomerRequest();customer.setBookingType("ADULT");
        var booking=BookingRequest.builder().bookingId("1").userId("7").tourDepartureId("1").listOfCustomers(List.of(customer)).build();request=InvoiceRequest.builder().bookingRequest(booking).build();
        var price=TourPriceResponse.builder().adultPrice("3350000").childPrice("0").toddlerPrice("0").infantPrice("0").singleSupplementPrice("0").build();
        // Deliberately stale snapshot: the writer must use current DB seats, not 999 from the remote response.
        var schedule=TourDepartureResponse.builder().id("1").tourId("1").availableSeats("999").tourPrice(price).build();
        when(departures.getTourDepartureById(1L)).thenReturn(ApiResponse.<TourDepartureResponse>builder().result(schedule).build());
        when(bookingClient.getBookingReservation(anyInt())).thenAnswer(inv -> {
            var rows = jdbc.queryForList("SELECT * FROM booking_reservation WHERE booking_id=?", inv.getArgument(0, Integer.class));
            return rows.isEmpty() ? java.util.Map.of() : rows.getFirst();
        });
        when(bookingClient.getBookingOwner(anyInt())).thenAnswer(inv -> {
            var rows = jdbc.queryForList("SELECT account_id FROM booking WHERE id=?", inv.getArgument(0, Integer.class));
            if (rows.isEmpty()) return null;
            return java.util.Map.of("account_id", rows.getFirst().get("ACCOUNT_ID"));
        });
        when(bookingClient.getBooking(anyInt())).thenAnswer(inv -> {
            var rows = jdbc.queryForList("""
                    SELECT b.id, b.account_id, b.tour_departure_id, b.status, d.departure_date, d.operational_status
                    FROM booking b JOIN tour_departure d ON d.id = b.tour_departure_id WHERE b.id=?
                    """, inv.getArgument(0, Integer.class));
            if (rows.isEmpty()) return null;
            var m = new java.util.HashMap<String, Object>();
            rows.getFirst().forEach((k, v) -> m.put(k.toLowerCase(), v));
            return m;
        });
        when(bookingClient.getBookingCustomers(anyInt())).thenAnswer(inv ->
            jdbc.queryForList("SELECT booking_type FROM customer WHERE booking_id=? ORDER BY id", inv.getArgument(0, Integer.class))
                .stream().map(r -> java.util.Map.<String, Object>of("booking_type", r.get("BOOKING_TYPE"))).toList());
        when(bookingClient.getBookingPriceSnapshot(anyInt())).thenAnswer(inv -> {
            var rows = jdbc.queryForList("SELECT total_amount FROM booking_price_snapshot WHERE booking_id=?", inv.getArgument(0, Integer.class));
            return rows.isEmpty() ? java.util.Map.of() : java.util.Map.of("total_amount", rows.getFirst().get("TOTAL_AMOUNT"));
        });
        when(bookingClient.getPaymentEvidence(anyInt())).thenAnswer(inv -> {
            int bId = inv.getArgument(0, Integer.class);
            LocalDateTime now = LocalDateTime.now(VIETNAM_ZONE);
            int c = jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM invoice WHERE booking_id=? AND status='PAID')
                  +(SELECT COUNT(*) FROM payment WHERE booking_id=? AND status='PAID')
                  +(SELECT COUNT(*) FROM vnpay_transaction WHERE booking_id=? AND (status='PAID' OR (status='PENDING' AND expires_at>?)))
                  +(SELECT COUNT(*) FROM momo_transaction WHERE booking_id=? AND (status='PAID' OR (status='PENDING' AND expires_at>?)))
                  +(SELECT COUNT(*) FROM payment_reconciliation WHERE booking_id=? AND status='OPEN')
                """, Integer.class, bId, bId, bId, now, bId, now, bId);
            return java.util.Map.of("evidence_count", c);
        });
        doAnswer(inv -> {
            int bId = inv.getArgument(0, Integer.class);
            var req = inv.getArgument(1, com.example.invoice_service.repository.httpclient.BookingInternalClient.DecrementSeatsRequest.class);
            int depId = jdbc.queryForObject("SELECT tour_departure_id FROM booking WHERE id=?", Integer.class, bId);
            int updated = jdbc.update("UPDATE tour_departure SET available_seats=available_seats-?,version=version+1 WHERE id=? AND available_seats>=?",
                    req.seats(), depId, req.seats());
            if (updated != 1) throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Chuyến đi không còn đủ chỗ");
            return null;
        }).when(bookingClient).decrementSeats(anyInt(), any());
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    int seats(){return jdbc.queryForObject("SELECT available_seats FROM tour_departure WHERE id=1",Integer.class);}
    @Test void suspendedTripCannotBeChargedThroughLegacyInvoice() {
        jdbc.update("UPDATE tour_departure SET operational_status='SUSPENDED' WHERE id=1");
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.payAndCreateInvoice(request)).getStatusCode().value());
        assertEquals(10,seats());assertEquals(0,invoices.count());
    }
    @Test void invoiceAmountUsesPersistedPassengerTypesNotBrowserPayload() {
        var tampered=new CustomerRequest();tampered.setBookingType("CHILD");
        request.getBookingRequest().setListOfCustomers(List.of(tampered));
        var invoice=service.payAndCreateInvoice(request);
        assertEquals("3350000",invoice.getTotalBookingTourExpense());
        assertEquals(9,seats());
    }
    @Test void consumesCurrentSeatsOnceAndRejectsDuplicateInvoice() {
        service.payAndCreateInvoice(request);assertEquals(9,seats());assertEquals(1,invoices.count());
        assertEquals(1L,jdbc.queryForObject("SELECT version FROM tour_departure WHERE id=1",Long.class));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.payAndCreateInvoice(request)).getStatusCode().value());
        assertEquals(9,seats());assertEquals(1,invoices.count());
    }
    @Test void laterFailureRollsBackBothInvoiceAndSeats() {
        jdbc.execute("ALTER TABLE invoice ADD CONSTRAINT invoice_test_check CHECK (total_booking_tour_expense > 999999999)");
        try {
            assertThrows(RuntimeException.class,()->service.payAndCreateInvoice(request));
            assertEquals(10,seats());assertEquals(0,invoices.count());
            assertEquals(0L,jdbc.queryForObject("SELECT version FROM tour_departure WHERE id=1",Long.class));
        } finally {
            jdbc.execute("ALTER TABLE invoice DROP CONSTRAINT invoice_test_check");
        }
    }
    @Test void delegatedCashierCanBillABookingTheyDoNotOwn() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "5","",List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("PERM:INVOICES_CREATE"))));
        var invoice=service.payAndCreateInvoice(request);
        assertEquals("7",invoice.getAccountId());
        assertEquals("1",invoice.getBookingId());
        assertEquals("3",invoice.getPaymentMethodId());
        assertEquals(9,seats());
        assertEquals(1,invoices.count());
    }
    @Test void unknownBookingIsReportedAsMissingNotAsServerFailure() {
        request.getBookingRequest().setBookingId("99");
        var failure=assertThrows(com.example.invoice_service.exception.AppException.class,
                ()->service.payAndCreateInvoice(request));
        assertEquals(com.example.invoice_service.exception.ErrorCode.BOOKING_NOT_EXISTED,failure.getErrorCode());
        assertEquals(0,invoices.count());
    }
    @Test void noCapacityAndPendingVnpayCannotCreateAnotherPaidInvoice() {
        jdbc.update("UPDATE tour_departure SET available_seats=0,capacity=0 WHERE id=1");
        assertThrows(ResponseStatusException.class,()->service.payAndCreateInvoice(request));assertEquals(0,invoices.count());
        jdbc.update("UPDATE tour_departure SET available_seats=10,capacity=10 WHERE id=1");
        jdbc.update("INSERT INTO vnpay_transaction VALUES('pending',1,'PENDING',?)",LocalDateTime.now(VIETNAM_ZONE).plusMinutes(10));
        assertThrows(ResponseStatusException.class,()->service.payAndCreateInvoice(request));assertEquals(10,seats());assertEquals(0,invoices.count());
    }
    @Test void pricingOutageIsReportedAsUnavailableWithoutConsumingSeats() {
        var schedule=TourDepartureResponse.builder().id("1").tourId("1").availableSeats("999").tourPrice(null).build();
        when(departures.getTourDepartureById(1L)).thenReturn(ApiResponse.<TourDepartureResponse>builder().result(schedule).build());
        var failure=assertThrows(ResponseStatusException.class,()->service.payAndCreateInvoice(request));
        assertEquals(503,failure.getStatusCode().value());
        assertEquals(10,seats());assertEquals(0,invoices.count());
    }
    @Test void pendingMomoCannotCreateAnotherPaidInvoice() {
        jdbc.update("INSERT INTO momo_transaction VALUES('pending-momo',1,'PENDING',?)",LocalDateTime.now(VIETNAM_ZONE).plusMinutes(10));
        assertThrows(ResponseStatusException.class,()->service.payAndCreateInvoice(request));
        assertEquals(10,seats());assertEquals(0,invoices.count());
    }
}
