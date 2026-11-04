package com.example.payment_service.service;

import com.example.payment_service.config.VnPayConfig;
import com.example.payment_service.dto.request.VnPayRequest;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VnPayBookingPaymentTest {
    final VnPayConfig config = new VnPayConfig("synthetic-test-hash-secret", "SYNTHETIC_TMN",
            "http://localhost:5173/payment/vnpay/callback",
            "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html");

    JdbcTemplate jdbc;
    TransactionTemplate transaction;
    VnPayOrderService orders;
    VnPayService service;
    final DateTimeFormatter date = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    @BeforeEach void setup() throws Exception {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        // Simulate MySQL Connector/J DATETIME values, unlike H2's default Timestamp.
        jdbc = new JdbcTemplate(ds) {
            private Map<String, Object> mysqlDates(Map<String, Object> row) {
                row.replaceAll((key, value) -> value instanceof java.sql.Timestamp timestamp
                    ? timestamp.toLocalDateTime() : value);
                return row;
            }
            @Override public Map<String, Object> queryForMap(String sql, Object... args) {
                return mysqlDates(super.queryForMap(sql, args));
            }
            @Override public List<Map<String, Object>> queryForList(String sql, Object... args) {
                var rows = super.queryForList(sql, args);
                rows.forEach(this::mysqlDates);
                return rows;
            }
        };
        transaction = new TransactionTemplate(new DataSourceTransactionManager(ds));
        orders = new VnPayOrderService(jdbc,
            new com.example.payment_service.notification.PaymentMailOutboxWriter(jdbc,
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()),
            Clock.system(ZoneId.of("Asia/Ho_Chi_Minh")));
        service = new VnPayService(orders, config);
        jdbc.execute("CREATE TABLE booking (id INT PRIMARY KEY, account_id INT, tour_departure_id INT, status VARCHAR(20))");
        jdbc.execute("CREATE TABLE tour_departure (id INT PRIMARY KEY, tour_id INT, departure_date TIMESTAMP, operational_status VARCHAR(20), available_seats INT, capacity INT NULL, version BIGINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE tour (id INT PRIMARY KEY, price_id INT)");
        jdbc.execute("CREATE TABLE prices (id INT PRIMARY KEY, adult_price DECIMAL(15,2), child_price DECIMAL(15,2), toddler_price DECIMAL(15,2), infant_price DECIMAL(15,2), single_supplement_price DECIMAL(15,2))");
        jdbc.execute("CREATE TABLE customer (id INT AUTO_INCREMENT PRIMARY KEY, booking_id INT, booking_type VARCHAR(20))");
        jdbc.execute("CREATE TABLE booking_price_snapshot (booking_id INT PRIMARY KEY, price_id BIGINT, adult_price DECIMAL(15,2), child_price DECIMAL(15,2), toddler_price DECIMAL(15,2), infant_price DECIMAL(15,2), single_supplement_price DECIMAL(15,2), total_amount DECIMAL(19,0), currency VARCHAR(3), snapshot_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE vnpay_transaction (txn_ref VARCHAR(32) PRIMARY KEY, booking_id INT, account_id INT, amount_vnd BIGINT, status VARCHAR(16), created_at TIMESTAMP, expires_at TIMESTAMP, transaction_no VARCHAR(255))");
        jdbc.execute("CREATE TABLE momo_transaction (order_id VARCHAR(32) PRIMARY KEY, request_id VARCHAR(32), booking_id INT, account_id INT, amount_vnd BIGINT, status VARCHAR(16), created_at TIMESTAMP, expires_at TIMESTAMP, trans_id BIGINT, result_code INT)");
        for (String table : List.of("invoice", "payment"))
            jdbc.execute("CREATE TABLE " + table + " (id INT AUTO_INCREMENT PRIMARY KEY, account_id INT, booking_id INT, day_of_pay TIMESTAMP, pay_method_id INT, status VARCHAR(20), total_booking_tour_expense DECIMAL(15,2))");
        jdbc.update("INSERT INTO booking VALUES (1,7,1,'CONFIRMED')");
        jdbc.update("INSERT INTO tour_departure (id,tour_id,departure_date,available_seats) VALUES (1,1,?,10)", LocalDateTime.now().plusDays(30));
        jdbc.update("INSERT INTO tour VALUES (1,1)");
        jdbc.update("INSERT INTO prices VALUES (1,8490000,6390000,0,500,1500000)");
        jdbc.update("INSERT INTO customer (booking_id,booking_type) VALUES (1,'ADULT'),(1,'ADULT'),(1,'CHILD')");
        jdbc.execute("CREATE TABLE users (id INT PRIMARY KEY,username VARCHAR(50),email VARCHAR(254))");
        jdbc.update("INSERT INTO users VALUES (7,'Danh','tester@example.com')");
        jdbc.execute("ALTER TABLE tour ADD tour_title VARCHAR(255)");
        jdbc.update("UPDATE tour SET tour_title='Đà Nẵng'");
        for (String file : List.of("001_mail_outbox.sql", "003_booking_reservation.sql")) {
            String ddl = new org.springframework.core.io.ClassPathResource("db/" + file).getContentAsString(java.nio.charset.StandardCharsets.UTF_8)
                .replace("CHARACTER SET ascii COLLATE ascii_bin", "")
                .replace("CONSTRAINT ck_mail_payload CHECK (JSON_VALID(payload)),", "")
                .replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci", "");
            for (String sql : ddl.split(";")) if (!sql.isBlank()) jdbc.execute(sql);
        }
    }

    VnPayOrderService.Order prepare(String amount) {
        return transaction.execute(s -> orders.prepare(1, 7, amount));
    }
    Map<String, String> callback(VnPayOrderService.Order order, boolean success) {
        Map<String, String> fields = new HashMap<>();
        fields.put("vnp_TxnRef", order.txnRef());
        fields.put("vnp_TmnCode", config.tmnCode());
        fields.put("vnp_Amount", Long.toString(order.amountVnd() * 100));
        fields.put("vnp_ResponseCode", success ? "00" : "24");
        fields.put("vnp_TransactionStatus", success ? "00" : "02");
        fields.put("vnp_PayDate", date.format(order.createdAt().plusMinutes(1)));
        fields.put("vnp_TransactionNo", "123456789");
        fields.put("vnp_OrderInfo", "Thanh toan booking 1");
        sign(fields);
        return fields;
    }
    void sign(Map<String, String> fields) {
        fields.remove("vnp_SecureHash");
        fields.put("vnp_SecureHash", VnPayConfig.hmacSHA512(config.secretKey(), VnPayService.canonicalQuery(fields)));
    }
    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    int seats() { return jdbc.queryForObject("SELECT available_seats FROM tour_departure WHERE id=1", Integer.class); }

    @Test void datesSupportBothMysqlAndTimestampAndRejectMissingValues() {
        var value = LocalDateTime.of(2026, 11, 1, 6, 0);
        assertEquals(value, VnPayOrderService.databaseDateTime(value));
        assertEquals(value, VnPayOrderService.databaseDateTime(java.sql.Timestamp.valueOf(value)));
        assertThrows(IllegalStateException.class, () -> VnPayOrderService.databaseDateTime(null));
    }
    @Test void selectedTourFareDoesNotIncludeUnselectedSingleRoom() {
        jdbc.update("DELETE FROM customer");
        jdbc.update("INSERT INTO customer (booking_id,booking_type) VALUES (1,'ADULT')");
        jdbc.update("UPDATE prices SET adult_price=3350000");
        assertEquals(3_350_000L, prepare("3350000").amountVnd());
    }
    @Test void mixedPassengersAndSingleTravelerUseDatabasePrices() {
        assertEquals(23_370_000L, prepare("23370000").amountVnd());
        jdbc.update("DELETE FROM customer WHERE booking_id=1");
        jdbc.update("INSERT INTO customer (booking_id,booking_type) VALUES (1,'ADULT')");
        jdbc.update("DELETE FROM vnpay_transaction");
        assertEquals(8_490_000L, prepare("8490000").amountVnd());
    }
    @Test void browserCannotChooseAmountOrAnotherAccountBooking() {
        assertThrows(IllegalArgumentException.class, () -> prepare("1500000"));
        assertThrows(ResponseStatusException.class, () -> transaction.execute(s -> orders.prepare(1, 99, "23370000")));
        assertEquals(0, count("vnpay_transaction"));
    }
    @Test void quoteUsesDatabasePricesAndNeverStartsATransaction() {
        var quote = transaction.execute(s -> orders.quote(1, 7, "vnpay"));
        assertEquals(23_370_000L, quote.amountVnd());
        assertEquals(0, new java.math.BigDecimal("8490000.00")
            .compareTo(new java.math.BigDecimal(quote.unitPrices().get("ADULT"))));
        assertEquals("6390000.00", quote.unitPrices().get("CHILD"));
        assertEquals(0, count("vnpay_transaction"));
    }
    @Test void quoteRejectsForeignOrPaidBooking() {
        assertThrows(ResponseStatusException.class, () -> transaction.execute(s -> orders.quote(1, 99, "vnpay")));
        jdbc.update("INSERT INTO invoice (account_id,booking_id,status) VALUES (7,1,'PAID')");
        assertThrows(IllegalArgumentException.class, () -> transaction.execute(s -> orders.quote(1, 7, "vnpay")));
    }
    /** The invoice a customer sees must not look payable when the pay button is already a 409. */
    @Test void quoteFailsForTheSameReasonTheOtherProvidersCheckoutWould() {
        jdbc.update("INSERT INTO momo_transaction (order_id,booking_id,account_id,amount_vnd,status,created_at,expires_at)"
                + " VALUES ('M1',1,7,23370000,'PENDING',?,?)",
            LocalDateTime.now(), LocalDateTime.now().plusMinutes(10));
        var blocked = assertThrows(ResponseStatusException.class,
            () -> transaction.execute(s -> orders.quote(1, 7, "vnpay")));
        assertEquals(org.springframework.http.HttpStatus.CONFLICT, blocked.getStatusCode());
        assertEquals(0, count("vnpay_transaction"));
        // A provider may still re-quote its own live order, which beginCheckout allows as well.
        assertEquals(23_370_000L, transaction.execute(s -> orders.quote(1, 7, "momo")).amountVnd());
        jdbc.update("UPDATE momo_transaction SET expires_at=? WHERE order_id='M1'", LocalDateTime.now().minusMinutes(1));
        assertEquals(23_370_000L, transaction.execute(s -> orders.quote(1, 7, "vnpay")).amountVnd());
    }
    @Test void emptyCanceledPastOrSoldOutBookingsCannotStartPayment() {
        jdbc.update("DELETE FROM customer");
        assertThrows(IllegalArgumentException.class, () -> prepare("1500000"));
        jdbc.update("INSERT INTO customer (booking_id,booking_type) VALUES (1,'ADULT')");
        jdbc.update("UPDATE booking SET status='CANCELLED'");
        assertThrows(IllegalArgumentException.class, () -> prepare("8490000"));
        jdbc.update("UPDATE booking SET status='CONFIRMED'");
        jdbc.update("UPDATE tour_departure SET departure_date=?", LocalDateTime.now().minusDays(1));
        assertThrows(IllegalArgumentException.class, () -> prepare("8490000"));
        jdbc.update("UPDATE tour_departure SET departure_date=?,available_seats=0", LocalDateTime.now().plusDays(2));
        assertThrows(IllegalArgumentException.class, () -> prepare("8490000"));
    }
    @Test void pendingRequestIsReusedAndAmountIsEncodedExactlyOnce() {
        var first = prepare("23370000");
        assertEquals(first.txnRef(), prepare("23370000").txnRef());
        var request = new VnPayRequest();
        request.setBookingId(1); request.setExpectedAmount("23370000"); request.setAmount("1");
        String url = transaction.execute(s -> service.createPayment(request, 7, new MockHttpServletRequest()));
        assertTrue(url.contains("vnp_Amount=2337000000&"));
        assertEquals(1, count("vnpay_transaction"));
    }
    @Test void invalidSignatureNeverSettlesOrConsumesSeats() {
        var fields = callback(prepare("23370000"), true);
        fields.put("vnp_Amount", "150000000");
        assertThrows(IllegalArgumentException.class, () -> transaction.execute(s -> service.handlePaymentReturn(fields)));
        assertEquals(0, count("invoice")); assertEquals(0, count("payment")); assertEquals(10, seats());
    }
    @Test void signedWrongAmountOrUnknownOrderIsRejected() {
        var fields = callback(prepare("23370000"), true);
        fields.put("vnp_Amount", "100"); sign(fields);
        assertThrows(IllegalArgumentException.class, () -> transaction.execute(s -> service.handlePaymentReturn(fields)));
        fields.put("vnp_TxnRef", "unknown"); sign(fields);
        assertThrows(IllegalArgumentException.class, () -> transaction.execute(s -> service.handlePaymentReturn(fields)));
        assertEquals(0, count("invoice"));
    }
    @Test void canceledPaymentDoesNotProducePaidInvoice() {
        var fields = callback(prepare("23370000"), false);
        assertEquals("failed", transaction.execute(s -> service.handlePaymentReturn(fields)).getStatus());
        assertEquals(0, count("invoice")); assertEquals(0, count("payment")); assertEquals(10, seats());
    }
    @Test void successAndReplayedReturnOrIpnSettleOnlyOnce() {
        var fields = callback(prepare("23370000"), true);
        var result = transaction.execute(s -> service.handlePaymentReturn(fields));
        assertEquals("success", result.getStatus()); assertEquals("23370000.00", result.getAmount());
        assertEquals(1, count("invoice")); assertEquals(1, count("payment")); assertEquals(7, seats());
        assertTrue(transaction.execute(s -> service.handlePaymentReturn(fields)).isAlreadyProcessed());
        assertEquals(1, count("invoice")); assertEquals(1, count("payment")); assertEquals(7, seats());
        assertEquals("PAID", jdbc.queryForObject("SELECT status FROM invoice", String.class));
        assertThrows(IllegalArgumentException.class, () -> prepare("23370000"));
    }
    @Test void failedDatabaseWriteRollsBackInvoiceSeatsAndTransaction() {
        var fields = callback(prepare("23370000"), true);
        jdbc.execute("ALTER TABLE payment ADD CONSTRAINT reject_payment CHECK(total_booking_tour_expense < 100)");
        assertThrows(RuntimeException.class, () -> transaction.execute(s -> service.handlePaymentReturn(fields)));
        assertEquals(0, count("invoice")); assertEquals(10, seats());
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM vnpay_transaction", String.class));
    }
    @Test void outOfWindowPaymentRequiresReconciliation() {
        var order = prepare("23370000"); var fields = callback(order, true);
        fields.put("vnp_PayDate", date.format(order.expiresAt().plusMinutes(1))); sign(fields);
        assertEquals("reconciliation", transaction.execute(s -> service.handlePaymentReturn(fields)).getStatus());
        assertEquals(0, count("invoice")); assertEquals(10, seats());
        assertEquals(1, count("payment_reconciliation"));
    }
}
