package com.example.booking_service.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

final class MailTestDatabase {
    final DriverManagerDataSource dataSource;
    final JdbcTemplate jdbc;
    final DataSourceTransactionManager transactions;
    final MailOutboxRepository repository;
    final String ddl;
    MailTestDatabase() throws Exception {
        String url = System.getenv("MAIL_TEST_JDBC_URL");
        boolean mysql = url != null && !url.isBlank();
        // Explicit isolated test port/schema only: never allow tests to touch live DBtour.
        if (mysql && !url.matches("jdbc:mysql://127\\.0\\.0\\.1:13317/mail_phase_b(?:\\?.*)?"))
            throw new IllegalArgumentException("Only isolated port 13317 / mail_phase_b is allowed");
        dataSource = new DriverManagerDataSource(mysql ? url
                : "jdbc:h2:mem:mail_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                mysql ? "root" : "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS mail_outbox");
        String source = new org.springframework.core.io.ClassPathResource("db/001_mail_outbox.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        if (!mysql) source = source.replace("CHARACTER SET ascii COLLATE ascii_bin", "")
                .replace("CONSTRAINT ck_mail_payload CHECK (JSON_VALID(payload)),", "")
                .replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci", "");
        ddl = source;
        jdbc.execute(ddl);
        transactions = new DataSourceTransactionManager(dataSource);
        repository = new MailOutboxRepository(jdbc, transactions);
    }
}
