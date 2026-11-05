package com.example.payment_service.reconciliation;

import com.example.payment_service.reservation.LifecycleTestDatabase;
import com.example.payment_service.service.VnPayOrderService;
import com.example.payment_service.notification.PaymentMailOutboxWriter;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ReconciliationTest {
    LifecycleTestDatabase db;
    ReconciliationService service;
    VnPayOrderService orders;
    final LocalDateTime time=LocalDateTime.of(2026,11,1,10,0);
    @BeforeEach void setup() throws Exception {
        db=new LifecycleTestDatabase();
        db.jdbc.update("INSERT INTO users(id,username,email) VALUES(1,'admin','admin@test.invalid')");
        String ddl=new org.springframework.core.io.ClassPathResource("db/reconciliation_event.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        int start=ddl.indexOf("CREATE TABLE IF NOT EXISTS payment_reconciliation_event");
        db.jdbc.execute(ddl.substring(start,ddl.indexOf(';',start)).replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci","").replace("CHARACTER SET ascii COLLATE ascii_bin",""));
        var clock=Clock.fixed(time.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant(),ZoneId.of("Asia/Ho_Chi_Minh"));
        service=new ReconciliationService(db.jdbc,clock);
        orders=new VnPayOrderService(db.jdbc,new PaymentMailOutboxWriter(db.jdbc,new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()),clock);
        db.jdbc.update("INSERT INTO booking(id,account_id,tour_departure_id,status,created_at) VALUES(1,7,1,'CONFIRMED',?)",time.minusDays(1));
        pending("transaction1");login(true);
    }
    @AfterEach void cleanup(){SecurityContextHolder.clearContext();}
    void login(boolean admin){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(admin?"1":"7","",admin?List.of(new SimpleGrantedAuthority("SGU_SUPER_ADMIN")):List.of()));}
    void pending(String ref){db.jdbc.update("INSERT INTO vnpay_transaction(txn_ref,booking_id,account_id,amount_vnd,status,created_at,expires_at) VALUES(?,1,7,3350000,'PENDING',?,?)",ref,time.minusHours(2),time.minusHours(1));}
    void pendingMoMo(String ref){db.jdbc.update("INSERT INTO momo_transaction(order_id,request_id,booking_id,account_id,amount_vnd,status,created_at,expires_at) VALUES(?,?,1,7,3350000,'PENDING',?,?)",ref,ref,time.minusHours(2),time.minusHours(1));}
    ReconciliationService.Decision decision(ReconciliationService.Action action,String reference){return new ReconciliationService.Decision(action,new BigDecimal("3350000"),reference,"Đã đối chiếu chứng từ bên ngoài",time.minusHours(2).plusMinutes(5),true);}
    Map<String,Object> resolve(String ref,ReconciliationService.Decision input){return db.tx.execute(tx->service.resolve(ref,input));}
    String status(){return db.jdbc.queryForObject("SELECT status FROM vnpay_transaction WHERE txn_ref='transaction1'",String.class);}
    Map<String,String> callback(boolean success){return Map.of("vnp_TxnRef","transaction1","vnp_Amount","335000000","vnp_ResponseCode",success?"00":"24","vnp_TransactionStatus",success?"00":"02","vnp_PayDate","20261101080500","vnp_TransactionNo","123456");}
    @Test void unpaidDecisionRequiresEvidenceAndDoesNotMarkBookingPaidOrMoveSeats() {
        assertEquals(1,service.list(0,"ACTIONABLE").items().size());
        int seats=db.seats();resolve("transaction1",decision(ReconciliationService.Action.UNPAID_CONFIRMED,"statement-1"));
        assertEquals("CLOSED_UNPAID",status());assertEquals(seats,db.seats());assertEquals(0,db.count("invoice"));assertEquals(0,db.count("payment"));
        assertEquals(0,service.list(0,"ACTIONABLE").items().size());assertEquals(1,service.list(0,"HISTORY").items().size());
        assertEquals(1,service.history("transaction1").size());
        resolve("transaction1",decision(ReconciliationService.Action.UNPAID_CONFIRMED,"statement-1"));assertEquals(1,db.count("payment_reconciliation_event"));
    }
    @Test void expiryAloneAndMissingConfirmationAreInsufficient() {
        var original=decision(ReconciliationService.Action.UNPAID_CONFIRMED,"statement-1");
        assertThrows(ResponseStatusException.class,()->resolve("transaction1",new ReconciliationService.Decision(original.action(),original.amountVnd(),original.reference(),original.note(),null,false)));
        assertEquals("PENDING",status());assertEquals(0,db.count("payment_reconciliation_event"));
        db.jdbc.update("UPDATE vnpay_transaction SET expires_at=?",time.plusMinutes(1));
        assertThrows(ResponseStatusException.class,()->resolve("transaction1",original));assertEquals("PENDING",status());
    }
    @Test void noPermissionAndInvalidMoneyAreRejected() {
        login(false);assertEquals(403,assertThrows(ResponseStatusException.class,()->service.list(0,"ACTIONABLE")).getStatusCode().value());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->resolve("transaction1",decision(ReconciliationService.Action.UNPAID_CONFIRMED,"statement-1"))).getStatusCode().value());
        login(true);
        for(BigDecimal amount:List.of(BigDecimal.ZERO,new BigDecimal("3350000.1"),new BigDecimal("1")))
            assertThrows(ResponseStatusException.class,()->resolve("transaction1",new ReconciliationService.Decision(ReconciliationService.Action.UNPAID_CONFIRMED,amount,"statement-1","checked",null,true)));
        assertEquals(0,db.count("payment_reconciliation_event"));
    }
    @Test void paidEvidenceCannotBeClosedAsUnpaidAndRefundNeedsAnOpenCase() {
        db.jdbc.update("UPDATE vnpay_transaction SET transaction_no='provider-evidence'");
        assertThrows(ResponseStatusException.class,()->resolve("transaction1",decision(ReconciliationService.Action.UNPAID_CONFIRMED,"statement-1")));
        assertThrows(ResponseStatusException.class,()->resolve("transaction1",decision(ReconciliationService.Action.REFUND_RECORDED,"refund-1")));
    }
    @Test void readPermissionDoesNotGrantSettlementAndWrongCaseReferenceIsRejected() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("1","",List.of(new SimpleGrantedAuthority("PERM:RECONCILIATION_READ"))));
        assertEquals(1,service.list(0,"ACTIONABLE").items().size());
        assertThrows(ResponseStatusException.class,()->resolve("transaction1",decision(ReconciliationService.Action.UNPAID_CONFIRMED,"statement-1")));
        login(true);
        // H2 lookup may be case-sensitive; either not-found or explicit conflict must prevent a write.
        assertThrows(ResponseStatusException.class,()->resolve("TRANSACTION1",decision(ReconciliationService.Action.UNPAID_CONFIRMED,"statement-1")));
        assertEquals("PENDING",status());assertEquals(0,db.count("payment_reconciliation_event"));
    }
    @Test void paymentFoundThenRefundClosesQueueWithFullAudit() {
        int seats=db.seats();resolve("transaction1",decision(ReconciliationService.Action.PAYMENT_FOUND,"statement-1"));
        assertEquals("OPEN",db.jdbc.queryForObject("SELECT status FROM payment_reconciliation",String.class));
        assertThrows(ResponseStatusException.class,()->resolve("transaction1",decision(ReconciliationService.Action.UNPAID_CONFIRMED,"statement-2")));
        resolve("transaction1",decision(ReconciliationService.Action.REFUND_RECORDED,"refund-1"));
        resolve("transaction1",decision(ReconciliationService.Action.REFUND_RECORDED,"refund-1"));
        assertEquals("REFUNDED",status());assertEquals("REFUNDED",db.jdbc.queryForObject("SELECT status FROM payment_reconciliation",String.class));
        assertEquals(2,db.count("payment_reconciliation_event"));assertEquals(0,service.list(0,"ACTIONABLE").items().size());
        assertEquals(seats,db.seats());assertEquals(0,db.count("invoice"));assertEquals(0,db.count("payment"));
        assertEquals("refunded",db.tx.execute(tx->orders.settle(callback(true))));assertEquals(2,db.count("payment_reconciliation_event"));
    }
    @Test void lateVerifiedPaymentAfterManualUnpaidDecisionReopensReconciliation() {
        resolve("transaction1",decision(ReconciliationService.Action.UNPAID_CONFIRMED,"statement-1"));
        assertEquals("reconciliation",db.tx.execute(tx->orders.settle(callback(true))));
        assertEquals("PENDING",status());assertEquals("OPEN",db.jdbc.queryForObject("SELECT status FROM payment_reconciliation",String.class));
        assertEquals(1,service.list(0,"ACTIONABLE").items().size());
        assertEquals("reconciliation",db.tx.execute(tx->orders.settle(callback(false))));
        assertEquals("PENDING",status());assertEquals(0,db.count("invoice"));
    }
    @Test void sameRefundReferenceCannotCloseTwoTransactions() {
        resolve("transaction1",decision(ReconciliationService.Action.PAYMENT_FOUND,"statement-1"));
        resolve("transaction1",decision(ReconciliationService.Action.REFUND_RECORDED,"refund-1"));
        pending("transaction2");resolve("transaction2",decision(ReconciliationService.Action.PAYMENT_FOUND,"statement-2"));
        assertThrows(ResponseStatusException.class,()->resolve("transaction2",decision(ReconciliationService.Action.REFUND_RECORDED,"refund-1")));
        assertEquals("OPEN",db.jdbc.queryForObject("SELECT status FROM payment_reconciliation WHERE txn_ref='transaction2'",String.class));
    }
    @Test void concurrentRefundRetriesWriteExactlyOneEvent() throws Exception {
        resolve("transaction1",decision(ReconciliationService.Action.PAYMENT_FOUND,"statement-1"));
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            List<Future<?>> futures=new ArrayList<>();
            for(int i=0;i<2;i++)futures.add(pool.submit(()->{login(true);start.await();try{return resolve("transaction1",decision(ReconciliationService.Action.REFUND_RECORDED,"refund-1"));}finally{SecurityContextHolder.clearContext();}}));
            start.countDown();for(var result:futures)result.get(20,TimeUnit.SECONDS);
            assertEquals(2,db.count("payment_reconciliation_event"));assertEquals("REFUNDED",status());
        }finally{pool.shutdownNow();}
    }
    @Test void momoTransactionsAreListedAndResolvedInTheirOwnTable() {
        pendingMoMo("momo1");
        var items=service.list(0,"ACTIONABLE").items();
        assertEquals(2,items.size());
        var momo=items.stream().filter(row->"momo1".equals(row.get("txn_ref"))).findFirst().orElseThrow();
        assertEquals("MOMO",momo.get("provider"));
        var result=resolve("momo1",decision(ReconciliationService.Action.UNPAID_CONFIRMED,"statement-momo"));
        assertEquals("MOMO",result.get("provider"));
        assertEquals("CLOSED_UNPAID",db.jdbc.queryForObject("SELECT status FROM momo_transaction WHERE order_id='momo1'",String.class));
        assertEquals("PENDING",status());
        // The unrelated VNPay fixture is still actionable; only the MoMo case left the queue.
        var remaining=service.list(0,"ACTIONABLE").items();
        assertEquals(1,remaining.size());
        assertEquals("transaction1",remaining.getFirst().get("txn_ref"));
        assertEquals(1,service.list(0,"HISTORY").items().size());
    }
    @Test void sameReferenceInBothProviderTablesIsRejected() {
        pendingMoMo("transaction1");
        assertEquals(409,assertThrows(ResponseStatusException.class,
                ()->resolve("transaction1",decision(ReconciliationService.Action.UNPAID_CONFIRMED,"statement-both"))).getStatusCode().value());
        assertEquals("PENDING",status());
        assertEquals("PENDING",db.jdbc.queryForObject("SELECT status FROM momo_transaction WHERE order_id='transaction1'",String.class));
        assertEquals(0,db.count("payment_reconciliation_event"));
    }
    @Test void refundOfMomoCaseUpdatesTheMomoTransaction() {
        pendingMoMo("momo2");
        resolve("momo2",decision(ReconciliationService.Action.PAYMENT_FOUND,"statement-momo"));
        resolve("momo2",decision(ReconciliationService.Action.REFUND_RECORDED,"refund-momo"));
        assertEquals("REFUNDED",db.jdbc.queryForObject("SELECT status FROM momo_transaction WHERE order_id='momo2'",String.class));
        assertEquals("REFUNDED",db.jdbc.queryForObject("SELECT status FROM payment_reconciliation WHERE txn_ref='momo2'",String.class));
        assertEquals("PENDING",status());
    }
}
