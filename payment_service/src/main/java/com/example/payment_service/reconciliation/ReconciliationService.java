package com.example.payment_service.reconciliation;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;

/** Records an operator's reconciliation evidence; never calls a bank or initiates a refund. */
@Service @RequiredArgsConstructor
public class ReconciliationService {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public enum Action { UNPAID_CONFIRMED, PAYMENT_FOUND, REFUND_RECORDED }
    /** Fixed provider whitelist; table and reference column are never taken from user input. */
    private enum Provider {
        VNPAY("vnpay_transaction", "txn_ref"), MOMO("momo_transaction", "order_id");
        private final String table;
        private final String refColumn;
        Provider(String table, String refColumn) { this.table = table; this.refColumn = refColumn; }
    }
    /** Both transaction shapes share the columns the reconciliation screen reads. */
    private static final String TRANSACTIONS = """
            SELECT txn_ref,booking_id,account_id,amount_vnd,status,created_at,expires_at,'VNPAY' AS provider
            FROM vnpay_transaction
            UNION ALL
            SELECT order_id,booking_id,account_id,amount_vnd,status,created_at,expires_at,'MOMO'
            FROM momo_transaction
            """;
    public record Decision(Action action,java.math.BigDecimal amountVnd,String reference,String note,
                           LocalDateTime providerPaidAt,boolean confirmed) {}
    public record Page(List<Map<String,Object>> items,boolean hasNext) {}
    private LocalDateTime now(){return LocalDateTime.now(clock).withNano(0);}
    private void require(boolean ok,String message){if(!ok)throw new ResponseStatusException(HttpStatus.CONFLICT,message);}
    private void invalid(String message){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private void ref(String ref){if(ref==null || !ref.matches("[A-Za-z0-9_-]{1,32}"))invalid("Mã giao dịch không hợp lệ");}
    private long actor(String permissionCode) {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null || !auth.isAuthenticated() || auth.getAuthorities().stream().noneMatch(a->
                a.getAuthority().equals("SGU_SUPER_ADMIN") || a.getAuthority().equals("PERM:"+permissionCode)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Không có quyền đối soát thanh toán");
        try{return Long.parseLong(auth.getName());}catch(NumberFormatException bad){throw new ResponseStatusException(HttpStatus.FORBIDDEN);}
    }
    public Page list(int page,String view) {
        actor("RECONCILIATION_READ");
        if(page<0 || page>100000 || !List.of("ACTIONABLE","HISTORY").contains(view))invalid("Bộ lọc đối soát không hợp lệ");
        String filter="HISTORY".equals(view)
                ? "EXISTS(SELECT 1 FROM payment_reconciliation_event e WHERE e.txn_ref=t.txn_ref)"
                : "(r.status='OPEN' OR (t.status='PENDING' AND t.expires_at<=? AND r.txn_ref IS NULL))";
        String sql="""
            SELECT t.txn_ref,t.booking_id,t.account_id,t.amount_vnd,t.status,t.created_at,t.expires_at,
                   t.provider,v.transaction_no,m.trans_id AS provider_trans_id,
                   u.username,u.email,b.status AS booking_status,r.status AS case_status,
                   r.reason,r.provider_paid_at FROM (%s) t
            JOIN booking b ON b.id=t.booking_id JOIN users u ON u.id=t.account_id
            LEFT JOIN payment_reconciliation r ON r.txn_ref=t.txn_ref
            LEFT JOIN vnpay_transaction v ON t.provider='VNPAY' AND v.txn_ref=t.txn_ref
            LEFT JOIN momo_transaction m ON t.provider='MOMO' AND m.order_id=t.txn_ref
            WHERE %s ORDER BY t.created_at DESC,t.txn_ref LIMIT 26 OFFSET ?
            """.formatted(TRANSACTIONS,filter);
        long offset=(long)page*25;
        var rows="HISTORY".equals(view)?jdbc.queryForList(sql,offset):jdbc.queryForList(sql,now(),offset);
        rows.forEach(row->{
            Object transId=row.remove("provider_trans_id");
            if(row.get("transaction_no")==null && transId!=null)row.put("transaction_no",String.valueOf(transId));
        });
        return new Page(rows.stream().limit(25).toList(),rows.size()>25);
    }
    public List<Map<String,Object>> history(String txnRef) {
        actor("RECONCILIATION_HISTORY");ref(txnRef);
        return jdbc.queryForList("""
            SELECT e.*,u.username AS actor_name FROM payment_reconciliation_event e
            LEFT JOIN users u ON u.id=e.actor_id WHERE txn_ref=? ORDER BY e.id DESC LIMIT 50
            """,txnRef);
    }
    // The first lookup must not pin a REPEATABLE_READ snapshot while another decision holds the row locks.
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED,timeout=10)
    public Map<String,Object> resolve(String txnRef,Decision input) {
        long actor=actor("RECONCILIATION_RESOLVE");ref(txnRef);
        validate(input);
        var lookup=jdbc.queryForList("SELECT booking_id,txn_ref,'VNPAY' AS provider FROM vnpay_transaction WHERE txn_ref=?"
                +" UNION ALL SELECT booking_id,order_id,'MOMO' FROM momo_transaction WHERE order_id=?",txnRef,txnRef);
        if(lookup.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy giao dịch");
        require(lookup.size()==1,"Mã giao dịch có ở nhiều nhà cung cấp; cần kiểm tra thủ công");
        require(txnRef.equals(lookup.getFirst().get("txn_ref")),"Mã giao dịch phải đúng chữ hoa/thường như danh sách đối soát");
        Provider provider=provider(String.valueOf(lookup.getFirst().get("provider")));
        int booking=((Number)lookup.getFirst().get("booking_id")).intValue();
        int departure=jdbc.queryForObject("SELECT tour_departure_id FROM booking WHERE id=?",Integer.class,booking);
        // Same lock order as checkout: departure, then booking. A booking whose schedule no longer
        // exists is a conflict the operator must see, not an EmptyResultDataAccessException 500.
        if (jdbc.queryForList("SELECT id FROM tour_departure WHERE id=? FOR UPDATE",departure).isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Lịch khởi hành không còn tồn tại");
        var b=jdbc.queryForMap("SELECT * FROM booking WHERE id=? FOR UPDATE",booking);
        require(((Number)b.get("tour_departure_id")).intValue()==departure,"Booking đã đổi lịch; hãy tải lại");
        var tx=lock(provider,txnRef);
        var cases=jdbc.queryForList("SELECT * FROM payment_reconciliation WHERE txn_ref=? FOR UPDATE",txnRef);
        var account=jdbc.queryForList("SELECT status FROM users WHERE id=? FOR UPDATE",actor);
        if(account.size()!=1 || !"ACTIVE".equals(account.getFirst().get("status")))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Tài khoản xử lý không hoạt động");
        long amount=((Number)tx.get("amount_vnd")).longValue();
        require(input.amountVnd().compareTo(java.math.BigDecimal.valueOf(amount))==0,"Số tiền đối soát không khớp giao dịch");
        var last=jdbc.queryForList("SELECT * FROM payment_reconciliation_event WHERE txn_ref=? ORDER BY id DESC LIMIT 1",txnRef);
        String completed=switch(input.action()){case UNPAID_CONFIRMED->"CLOSED_UNPAID";case PAYMENT_FOUND->"PENDING";case REFUND_RECORDED->"REFUNDED";};
        if(!last.isEmpty() && last.getFirst().get("action").equals(input.action().name())
                && last.getFirst().get("reference").equals(input.reference()) && last.getFirst().get("note").equals(input.note())
                && completed.equals(tx.get("status"))) {
            if(input.action()!=Action.PAYMENT_FOUND)return response(provider,txnRef);
            require(!cases.isEmpty() && "OPEN".equals(cases.getFirst().get("status"))
                    && Objects.equals(input.providerPaidAt(),date(cases.getFirst().get("provider_paid_at"))),"Kết quả đã được ghi nhận với thời gian khác");
            return response(provider,txnRef);
        }
        require(jdbc.queryForObject("SELECT COUNT(*) FROM payment_reconciliation_event WHERE action=? AND reference=?",Integer.class,input.action().name(),input.reference())==0,
                "Tham chiếu đã được ghi nhận; hãy tải lại lịch sử để kiểm tra");
        switch(input.action()) {
            case UNPAID_CONFIRMED -> {
                require("PENDING".equals(tx.get("status")) && cases.isEmpty() && !evidence(tx),"Chỉ đóng giao dịch chờ chưa có bằng chứng thu tiền");
                require(!date(tx.get("expires_at")).isAfter(now()),"Giao dịch chưa hết hạn");
                jdbc.update("UPDATE "+provider.table+" SET status='CLOSED_UNPAID' WHERE "+provider.refColumn+"=?",txnRef);
            }
            case PAYMENT_FOUND -> {
                require("PENDING".equals(tx.get("status")) && cases.isEmpty(),"Giao dịch đã có kết quả hoặc đang được đối soát");
                require(!date(tx.get("expires_at")).isAfter(now()),"Chưa xử lý thủ công giao dịch đang trong hạn thanh toán");
                if(input.providerPaidAt()==null || input.providerPaidAt().isBefore(date(tx.get("created_at"))) || input.providerPaidAt().isAfter(now().plusMinutes(5)))invalid("Cần giờ thu tiền hợp lệ theo chứng từ");
                jdbc.update("INSERT INTO payment_reconciliation(txn_ref,booking_id,reason,status,provider_paid_at,amount_vnd,created_at) VALUES (?,?,'MANUAL_PAYMENT_FOUND','OPEN',?,?,?)",
                        txnRef,booking,input.providerPaidAt(),amount,now());
            }
            case REFUND_RECORDED -> {
                require(cases.size()==1 && "OPEN".equals(cases.getFirst().get("status")),"Chỉ ghi nhận hoàn tiền cho ca đối soát đang mở");
                require(((Number)cases.getFirst().get("amount_vnd")).longValue()==amount,"Số tiền ca đối soát không khớp");
                require(!"PAID".equals(tx.get("status")),"Giao dịch đã hoàn tất booking; cần kiểm tra chứng từ trước");
                jdbc.update("UPDATE payment_reconciliation SET status='REFUNDED' WHERE txn_ref=?",txnRef);
                jdbc.update("UPDATE "+provider.table+" SET status='REFUNDED' WHERE "+provider.refColumn+"=?",txnRef);
            }
        }
        try {
            jdbc.update("INSERT INTO payment_reconciliation_event(txn_ref,booking_id,actor_id,action,amount_vnd,reference,note,created_at) VALUES (?,?,?,?,?,?,?,?)",
                    txnRef,booking,actor,input.action().name(),amount,input.reference(),input.note(),now());
        }catch(org.springframework.dao.DuplicateKeyException duplicate){throw new ResponseStatusException(HttpStatus.CONFLICT,"Tham chiếu vừa được sử dụng; hãy tải lại",duplicate);}
        jdbc.update("UPDATE tour_departure SET version=version+1 WHERE id=?",departure);
        return response(provider,txnRef);
    }
    /** Reads the locked row from the whitelisted provider table and exposes a common key set. */
    private Map<String,Object> lock(Provider provider,String txnRef) {
        var tx=jdbc.queryForMap("SELECT * FROM "+provider.table+" WHERE "+provider.refColumn+"=? FOR UPDATE",txnRef);
        if(tx.get("trans_id")!=null)tx.put("transaction_no",tx.get("trans_id"));
        return tx;
    }
    private Map<String,Object> response(Provider provider,String txnRef) {
        var tx=jdbc.queryForMap("SELECT * FROM "+provider.table+" WHERE "+provider.refColumn+"=?",txnRef);
        // Normalize labels so the JSON contract does not depend on the database's identifier casing.
        var row=new LinkedHashMap<String,Object>();
        tx.forEach((column,value)->row.put(column.toLowerCase(Locale.ROOT),value));
        row.put("txn_ref",txnRef);
        row.put("provider",provider.name());
        if(tx.get("trans_id")!=null)row.put("transaction_no",String.valueOf(tx.get("trans_id")));
        return row;
    }
    /** Provider-side proof that money moved; MoMo stores it as trans_id instead of transaction_no. */
    private static boolean evidence(Map<String,Object> tx) {
        return tx.get("transaction_no")!=null || tx.get("trans_id")!=null;
    }
    private static Provider provider(String value) {
        try { return Provider.valueOf(value); }
        catch (IllegalArgumentException unknown) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Giao dịch thuộc nhà cung cấp không được hỗ trợ");
        }
    }
    private void validate(Decision input) {
        if(input==null || input.action()==null || !input.confirmed() || input.amountVnd()==null || input.amountVnd().signum()<=0
                || input.amountVnd().stripTrailingZeros().scale()>0 || input.amountVnd().compareTo(java.math.BigDecimal.valueOf(9_999_999_999L))>0
                || input.reference()==null || !input.reference().matches("[A-Za-z0-9._:/-]{3,100}")
                || input.note()==null || input.note().isBlank() || input.note().length()>1000
                || input.note().codePoints().anyMatch(c->Character.isISOControl(c) && c!='\n' && c!='\r' && c!='\t'))invalid("Cần xác nhận đã đối chiếu, số tiền, tham chiếu và ghi chú hợp lệ (tối đa 1000 ký tự)");
    }
    private LocalDateTime date(Object value){return value instanceof LocalDateTime d?d:((java.sql.Timestamp)value).toLocalDateTime();}
}
