package com.example.tour_service.advice;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import javax.sql.DataSource;

/** Fixed test bean: environment datasource overrides cannot redirect schema initialization to DBtour. */
@TestConfiguration(proxyBeanMethods=false)
public class AdviceTestDatabase {
    @Bean @Primary DataSource adviceTestDataSource() {
        return new DriverManagerDataSource("jdbc:h2:mem:advice_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
    }
}
