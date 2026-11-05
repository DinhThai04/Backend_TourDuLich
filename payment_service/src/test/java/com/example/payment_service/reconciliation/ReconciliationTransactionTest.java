package com.example.payment_service.reconciliation;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** Exercise the real Spring proxies over a pool whose default isolation matches local MariaDB. */
@DisabledIfEnvironmentVariable(named="LIFECYCLE_TEST_JDBC_URL",matches=".+")
class ReconciliationTransactionTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    ReconciliationTest fixture;
    AnnotationConfigApplicationContext context;
    RecordingJdbc jdbc;
    ReconciliationAttempts attempts;
    DataSource source;

    @BeforeEach void setup() throws Exception {
        fixture=new ReconciliationTest();fixture.setup();
        fixture.resolve("transaction1",fixture.decision(ReconciliationService.Action.PAYMENT_FOUND,"statement-1"));
        source=new DelegatingDataSource(fixture.db.jdbc.getDataSource()) {
            @Override public Connection getConnection() throws SQLException {
                var connection=super.getConnection();connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);return connection;
            }
            @Override public Connection getConnection(String username,String password) throws SQLException {
                var connection=super.getConnection(username,password);connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);return connection;
            }
        };
        jdbc=new RecordingJdbc(source);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class);
        context.registerBean(DataSource.class,()->source);
        context.registerBean(JdbcTemplate.class,()->jdbc);
        context.registerBean(PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,()->Clock.fixed(fixture.time.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant(),ZoneId.of("Asia/Ho_Chi_Minh")));
        context.registerBean(com.example.payment_service.notification.PaymentMailOutboxWriter.class,()->new com.example.payment_service.notification.PaymentMailOutboxWriter(jdbc,new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()));
        context.registerBean(com.example.payment_service.service.VnPayOrderService.class);
        context.registerBean(ReconciliationService.class);context.registerBean(ReconciliationAttempts.class);context.refresh();
        attempts=context.getBean(ReconciliationAttempts.class);
    }
    @AfterEach void cleanup(){if(context!=null)context.close();if(fixture!=null)fixture.cleanup();}

    @Test void identicalConcurrentRefundsObserveCommittedAuditDespiteRepeatableReadDefault() throws Exception {
        jdbc.gate=new CyclicBarrier(2);
        var pool=Executors.newFixedThreadPool(2);
        try {
            List<Future<Map<String,Object>>> results=new ArrayList<>();
            for(int i=0;i<2;i++)results.add(pool.submit(()->{
                fixture.login(true);
                try{return attempts.resolve("transaction1",fixture.decision(ReconciliationService.Action.REFUND_RECORDED,"refund-1"));}
                finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();}
            }));
            for(var result:results)assertEquals("REFUNDED",result.get(20,TimeUnit.SECONDS).get("status"));
            assertEquals(List.of(Connection.TRANSACTION_READ_COMMITTED,Connection.TRANSACTION_READ_COMMITTED),jdbc.isolations);
            assertUnchangedMoneyAndOneRefund();
            try(var connection=source.getConnection()){assertEquals(Connection.TRANSACTION_REPEATABLE_READ,connection.getTransactionIsolation());}
        }finally{pool.shutdownNow();}
    }

    @Test void aLockFailureAfterAuditInsertRollsBackBeforeTheBoundedRetry() {
        jdbc.failAfterAudit.set(true);
        assertEquals("REFUNDED",attempts.resolve("transaction1",fixture.decision(ReconciliationService.Action.REFUND_RECORDED,"refund-1")).get("status"));
        assertEquals(List.of(Connection.TRANSACTION_READ_COMMITTED,Connection.TRANSACTION_READ_COMMITTED),jdbc.isolations);
        assertUnchangedMoneyAndOneRefund();
    }

    private void assertUnchangedMoneyAndOneRefund() {
        assertEquals("REFUNDED",fixture.status());
        assertEquals(2,fixture.db.count("payment_reconciliation_event")); // PAYMENT_FOUND + exactly one committed refund.
        assertEquals(0,fixture.db.count("invoice"));assertEquals(0,fixture.db.count("payment"));assertEquals(10,fixture.db.seats());
    }

    private com.example.payment_service.service.VnPayOrderService orderFixture() {
        fixture.db.jdbc.update("DELETE FROM payment_reconciliation_event");fixture.db.jdbc.update("DELETE FROM payment_reconciliation");
        fixture.db.jdbc.update("INSERT INTO customer(booking_id,booking_type) VALUES(1,'ADULT')");
        return context.getBean(com.example.payment_service.service.VnPayOrderService.class);
    }

    @Test void concurrentPrepareReusesTheSameCurrentTransactionOnARepeatableReadPool() throws Exception {
        var orders=orderFixture();jdbc.prefix="SELECT tour_departure_id FROM booking WHERE id=?";jdbc.gate=new CyclicBarrier(2);
        var pool=Executors.newFixedThreadPool(2);
        try {
            List<Future<com.example.payment_service.service.VnPayOrderService.Order>> results=new ArrayList<>();
            for(int i=0;i<2;i++)results.add(pool.submit(()->orders.prepare(1,7,"3350000")));
            assertEquals(results.get(0).get(20,TimeUnit.SECONDS).txnRef(),results.get(1).get(20,TimeUnit.SECONDS).txnRef());
            assertEquals(2,fixture.db.count("vnpay_transaction")); // old expired attempt plus one current attempt.
            assertEquals(0,fixture.db.count("invoice"));assertEquals(10,fixture.db.seats());
            assertEquals(List.of(Connection.TRANSACTION_READ_COMMITTED,Connection.TRANSACTION_READ_COMMITTED),jdbc.isolations);
        }finally{pool.shutdownNow();}
    }

    @Test void twoDifferentCallbacksCannotCreateTwoReceiptsOrConsumeSeatsTwice() throws Exception {
        var orders=orderFixture();
        fixture.db.jdbc.update("UPDATE vnpay_transaction SET created_at=?,expires_at=?",fixture.time.minusMinutes(5),fixture.time.plusMinutes(15));
        fixture.db.jdbc.update("INSERT INTO vnpay_transaction(txn_ref,booking_id,account_id,amount_vnd,status,created_at,expires_at) VALUES('transaction2',1,7,3350000,'PENDING',?,?)",fixture.time.minusMinutes(5),fixture.time.plusMinutes(15));
        jdbc.prefix="SELECT booking_id FROM vnpay_transaction WHERE txn_ref=?";jdbc.gate=new CyclicBarrier(2);
        var pool=Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> results=new ArrayList<>();
            for(String reference:List.of("transaction1","transaction2"))results.add(pool.submit(()->{
                var fields=new HashMap<>(fixture.callback(true));fields.put("vnp_TxnRef",reference);fields.put("vnp_PayDate","20261101100000");
                return orders.settle(fields);
            }));
            var statuses=new HashSet<String>();for(var result:results)statuses.add(result.get(20,TimeUnit.SECONDS));
            assertEquals(Set.of("success","reconciliation"),statuses);
            assertEquals(1,fixture.db.count("invoice"));assertEquals(1,fixture.db.count("payment"));assertEquals(9,fixture.db.seats());
            assertEquals(1,fixture.db.count("payment_reconciliation"));
            assertEquals(List.of(Connection.TRANSACTION_READ_COMMITTED,Connection.TRANSACTION_READ_COMMITTED),jdbc.isolations);
        }finally{pool.shutdownNow();}
    }

    static class RecordingJdbc extends JdbcTemplate {
        final List<Integer> isolations=new CopyOnWriteArrayList<>();
        final AtomicBoolean failAfterAudit=new AtomicBoolean();
        volatile CyclicBarrier gate;
        volatile String prefix="SELECT booking_id,txn_ref,'VNPAY' AS provider FROM vnpay_transaction";
        RecordingJdbc(DataSource source){super(source);}
        @Override public List<Map<String,Object>> queryForList(String sql,Object... values) {
            var rows=super.queryForList(sql,values);
            if(sql.startsWith(prefix)) {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                var connection=DataSourceUtils.getConnection(getDataSource());
                try{isolations.add(connection.getTransactionIsolation());}
                catch(SQLException error){throw new AssertionError(error);}
                finally{DataSourceUtils.releaseConnection(connection,getDataSource());}
                if(gate!=null)try{gate.await(10,TimeUnit.SECONDS);}catch(Exception error){throw new AssertionError(error);}
            }
            return rows;
        }
        @Override public int update(String sql,Object... values) {
            int changed=super.update(sql,values);
            if(sql.startsWith("INSERT INTO payment_reconciliation_event") && failAfterAudit.compareAndSet(true,false))
                throw new CannotAcquireLockException("Fixture: transaction loses a lock after its audit write");
            return changed;
        }
    }
}
