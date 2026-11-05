package com.example.payment_service.reservation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;

public final class LifecycleTestDatabase {
    public final JdbcTemplate jdbc;
    public final DataSourceTransactionManager manager;
    public final TransactionTemplate tx;
    public LifecycleTestDatabase() throws Exception {
        String url = System.getenv("LIFECYCLE_TEST_JDBC_URL");
        boolean mysql = url != null && !url.isBlank();
        if (mysql && !url.matches("jdbc:mysql://127\\.0\\.0\\.1:13318/lifecycle_phase_c(?:\\?.*)?"))
            throw new IllegalArgumentException("Only isolated port 13318 / lifecycle_phase_c allowed");
        var ds = new DriverManagerDataSource(mysql ? url : "jdbc:h2:mem:life_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", mysql ? "root" : "sa", "");
        jdbc = new JdbcTemplate(ds);
        manager = new DataSourceTransactionManager(ds); tx = new TransactionTemplate(manager);
        for (String table : List.of("booking_reservation","payment_reconciliation","mail_outbox","customer","booking_price_snapshot","momo_transaction","vnpay_transaction","payment","invoice","booking","tour_departure","tour","prices","users"))
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        run("db/lifecycle_schema.sql", mysql);
        run("db/001_mail_outbox.sql", mysql);
        run("db/003_booking_reservation.sql", mysql);
        jdbc.execute("CREATE TABLE IF NOT EXISTS momo_transaction (order_id VARCHAR(32) PRIMARY KEY, request_id VARCHAR(32), booking_id INT, account_id INT, amount_vnd BIGINT, status VARCHAR(16), created_at DATETIME(6), expires_at DATETIME(6), trans_id BIGINT, result_code INT)");
        jdbc.update("INSERT INTO users (id,username,email) VALUES (7,'Danh','tester@example.com'),(8,'Other','other@example.com')");
        jdbc.update("INSERT INTO prices VALUES (1,3350000,2000000,0,500,1500000)");
        jdbc.update("INSERT INTO tour VALUES (1,1,'Đà Nẵng – Hội An')");
        jdbc.update("INSERT INTO tour_departure (id,tour_id,departure_date,available_seats) VALUES (1,1,?,10)", LocalDateTime.of(2026,11,20,6,0));
    }
    public void run(String path, boolean mysql) throws Exception {
        String ddl = new org.springframework.core.io.ClassPathResource(path).getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        if (!mysql) ddl = ddl.replace("CHARACTER SET ascii COLLATE ascii_bin", "")
                .replace("CONSTRAINT ck_mail_payload CHECK (JSON_VALID(payload)),", "")
                .replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci", "");
        for (String sql : ddl.split(";")) if (!sql.isBlank()) jdbc.execute(sql);
    }
    public int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    public int seats() { return jdbc.queryForObject("SELECT available_seats FROM tour_departure WHERE id=1", Integer.class); }
    public static class MutableClock extends Clock {
        private volatile Instant instant;
        public MutableClock(LocalDateTime value) { set(value); }
        public void set(LocalDateTime value) { instant = value.atZone(getZone()).toInstant(); }
        public ZoneId getZone() { return ZoneId.of("Asia/Ho_Chi_Minh"); }
        public Clock withZone(ZoneId zone) { return Clock.fixed(instant, zone); }
        public Instant instant() { return instant; }
    }
}

