package com.example.booking_service.advice;

import com.example.booking_service.reservation.BookingAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in SELECT-only compatibility check. A read-only transaction is enforced by MariaDB/MySQL. */
@EnabledIfEnvironmentVariable(named="SGU_AI_READONLY_DB",matches="true")
class BookingAdviceReadOnlyMysqlTest {
    @Test void existingMysqlProjectionIsOwnerScopedAndDoesNotChangeBusinessRecords() throws Exception {
        String password=System.getenv().getOrDefault("TOUR_DB_PASSWORD","root");
        try(var connection=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/dbtour_v2?connectTimeout=3000&socketTimeout=5000","root",password)) {
            connection.setReadOnly(true);connection.setAutoCommit(false);
            var jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
            var before=counts(jdbc);
            var owners=jdbc.queryForList("SELECT DISTINCT account_id FROM booking ORDER BY account_id",Integer.class);
            int checked=0;
            for(int owner:owners) {
                var jwt=Jwt.withTokenValue("readonly-local-fixture").header("alg","HS512").subject(""+owner).build();
                SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,List.of()));
                var service=new BookingAdviceService(jdbc,new BookingAccess(),Clock.system(ZoneId.of("Asia/Ho_Chi_Minh")));
                var page=service.mine(null);
                for(var item:page.bookings()) {
                    assertEquals(owner,jdbc.queryForObject("SELECT account_id FROM booking WHERE id=?",Integer.class,item.bookingId()));
                    assertTrue(item.remainingSeconds()>=0);assertEquals("reservation-v1",item.ruleVersion());checked++;
                }
                var other=jdbc.queryForList("SELECT id FROM booking WHERE account_id<>? ORDER BY id LIMIT 1",Integer.class,owner);
                if(!other.isEmpty())assertTrue(service.mine(other.getFirst()).bookings().isEmpty());
            }
            assertEquals(before,counts(jdbc));connection.rollback();
            assertFalse(owners.isEmpty());
            System.out.println("READONLY_BOOKING_ADVICE owners="+owners.size()+" checked="+checked+" counts_unchanged=true; no provider/mail/booking writes");
        } finally {SecurityContextHolder.clearContext();}
    }
    private Map<String,Integer> counts(JdbcTemplate jdbc) {
        var result=new LinkedHashMap<String,Integer>();
        for(String table:List.of("booking","booking_reservation","customer","tour_departure","payment","invoice","vnpay_transaction","payment_reconciliation","mail_outbox"))
            result.put(table,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));
        return result;
    }
}
