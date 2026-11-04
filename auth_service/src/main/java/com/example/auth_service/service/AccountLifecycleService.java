package com.example.auth_service.service;

import com.example.auth_service.config.AccountAccess;
import com.example.auth_service.entity.*;
import com.example.auth_service.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.List;

@Service @RequiredArgsConstructor
public class AccountLifecycleService {
    private final UserRepository users;
    private final UserAccountEventRepository events;
    private final JdbcTemplate jdbc;
    private final AccountAccess access;
    private final AdminContinuity adminContinuity;
    @Value("${app.registration.customer-role-name:Khách Hàng}") private String customerRoleName;
    private ResponseStatusException conflict(String text){return new ResponseStatusException(HttpStatus.CONFLICT,text);}
    private void require(boolean condition,String message){if(!condition)throw conflict(message);}
    private String reason(String text){
        if(text==null || text.isBlank() || text.length()>1000 || text.codePoints().anyMatch(c->Character.isISOControl(c) && c!='\n' && c!='\r' && c!='\t'))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Cần lý do hợp lệ, tối đa 1000 ký tự");
        return text.trim();
    }
    private long actorId(){
        var auth=SecurityContextHolder.getContext().getAuthentication();
        try {if(auth==null || !auth.isAuthenticated())throw new IllegalArgumentException();return Long.parseLong(auth.getName());}
        catch(RuntimeException invalid){throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Cần đăng nhập quản trị");}
    }
    private User target(long id,String permissionCode){
        // Serialize administrator lifecycle changes, then lock the target account.
        adminContinuity.lockRoles();
        long actor=actorId();
        if(actor==id)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Không được tự khóa, lưu trữ hoặc xóa tài khoản đang sử dụng");
        var current=users.findById(actor).orElseThrow(()->new ResponseStatusException(HttpStatus.FORBIDDEN,"Tài khoản quản trị không còn tồn tại"));
        adminContinuity.refresh(current);
        if(current.getStatus()!=UserStatus.ACTIVE || !access.can(permissionCode))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Không có quyền quản lý trạng thái tài khoản");
        var target=users.lockForLifecycle(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy tài khoản"));
        adminContinuity.refresh(target);
        boolean admin=current.getRole()!=null && "admin".equalsIgnoreCase(current.getRole().getName());
        if(!admin && (target.getRole()==null || !customerRoleName.equalsIgnoreCase(target.getRole().getName())))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Nhân viên chỉ được quản lý trạng thái tài khoản khách hàng");
        adminContinuity.requireUserRoleChange(id,null);
        return target;
    }
    private int count(String sql,Object... args){return jdbc.queryForObject(sql,Integer.class,args);}
    /** One clock for this service: the app writes Asia/Ho_Chi_Minh timestamps, so it must read them with the same clock. */
    private static LocalDateTime now(){return LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh"));}
    private void resolved(long id){
        LocalDateTime current=now();
        require(count("SELECT COUNT(*) FROM booking b LEFT JOIN tour_departure d ON d.id=b.tour_departure_id WHERE b.account_id=? AND b.status IN ('PENDING_PAYMENT','CONFIRMED') AND (d.id IS NULL OR d.return_date IS NULL OR d.return_date>?)",id,current)==0,"Tài khoản còn chuyến đi chưa hoàn tất");
        // Same "money still in flight" predicate as ReservationExpiryService: a PENDING row past its
        // expiry is reconciliation work, so blocking archival on it forever would strand the account.
        require(count("SELECT COUNT(*) FROM vnpay_transaction WHERE account_id=? AND status='PENDING' AND expires_at>?",id,current)==0,"Còn giao dịch VNPay chưa có kết quả cuối cùng");
        require(count("SELECT COUNT(*) FROM momo_transaction WHERE account_id=? AND status='PENDING' AND expires_at>?",id,current)==0,"Còn giao dịch MoMo chưa có kết quả cuối cùng");
        require(count("SELECT COUNT(*) FROM payment_reconciliation p JOIN booking b ON b.id=p.booking_id WHERE b.account_id=? AND p.status='OPEN'",id)==0,"Còn giao dịch cần đối soát");
        require(count("SELECT COUNT(*) FROM booking_change WHERE account_id=? AND active_key=1",id)==0,"Còn yêu cầu dời lịch hoặc hoàn tiền chưa giải quyết");
    }
    private boolean hasHistory(long id){
        for(String sql:List.of("SELECT COUNT(*) FROM booking WHERE account_id=?","SELECT COUNT(*) FROM payment WHERE account_id=?",
                "SELECT COUNT(*) FROM invoice WHERE account_id=?","SELECT COUNT(*) FROM reviews WHERE user_id=?",
                "SELECT COUNT(*) FROM booking_change WHERE account_id=?","SELECT COUNT(*) FROM booking_change_event WHERE actor_id=?",
                "SELECT COUNT(*) FROM departure_change WHERE created_by=?","SELECT COUNT(*) FROM mail_outbox WHERE account_id=?",
                "SELECT COUNT(*) FROM payment_reconciliation_event WHERE actor_id=?"))
            if(count(sql,id)>0)return true;
        return false;
    }
    private void audit(User target,String action,UserStatus next,String text){
        var event=new UserAccountEvent();event.setTargetUserId(target.getId());event.setActorId(actorId());event.setAction(action);
        event.setPreviousStatus(target.getStatus().name());event.setNewStatus(next==null?null:next.name());event.setReason(text);
        event.setCreatedAt(now());events.save(event);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public User status(long id,UserStatus next,String why){
        String text=reason(why);
        if(next!=UserStatus.ACTIVE && next!=UserStatus.LOCKED)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Dùng thao tác lưu trữ riêng");
        var target=target(id,"USER_STATUS_UPDATE");
        require(target.getStatus()!=UserStatus.ARCHIVED,"Tài khoản đã lưu trữ không được mở lại");
        require(target.getStatus()!=next,"Tài khoản đã ở trạng thái này; hãy tải lại danh sách");
        audit(target,next==UserStatus.LOCKED?"LOCK":"UNLOCK",next,text);
        target.setStatus(next);return users.save(target);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public User archive(long id,String why){
        String text=reason(why);var target=target(id,"USER_ARCHIVE");
        require(target.getStatus()==UserStatus.LOCKED,"Cần khóa tài khoản trước khi lưu trữ");resolved(id);
        audit(target,"ARCHIVE",UserStatus.ARCHIVED,text);target.setStatus(UserStatus.ARCHIVED);return users.save(target);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void delete(long id,String why){
        String text=reason(why);
        if(id<1)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Mã tài khoản không hợp lệ");
        // Same owner-before-user order as AvatarProfileWriter, under the existing role fence.
        adminContinuity.lockRoles();String owner=Long.toString(id);
        jdbc.update("INSERT INTO avatar_media_lock(owner_id) VALUES (?) ON DUPLICATE KEY UPDATE owner_id=owner_id",owner);
        jdbc.queryForObject("SELECT owner_id FROM avatar_media_lock WHERE owner_id=? FOR UPDATE",String.class,owner);
        var target=target(id,"USERS_DELETE");
        require(target.getStatus()==UserStatus.LOCKED,"Chỉ xóa thật tài khoản đã khóa");
        require(!hasHistory(id),"Tài khoản đã có lịch sử nghiệp vụ; hãy lưu trữ thay vì xóa thật");
        resolved(id);audit(target,"DELETE",null,text);
        // Retain metadata for explicit Cloudinary cleanup, without leaving an active avatar for a deleted owner.
        jdbc.update("UPDATE file_mgmt SET avatar_owner_id=NULL,owner_id='',tour_id='null' WHERE avatar_owner_id=? OR (owner_id=? AND (tour_id IS NULL OR tour_id='null') AND location_id IS NULL)",owner,owner);
        users.delete(target);
    }
    public boolean manageable(User target) {
        try {
            long actor=actorId();if(actor==target.getId())return false;
            var current=users.findById(actor).orElse(null);
            return current!=null && current.getStatus()==UserStatus.ACTIVE && current.getRole()!=null &&
                ("admin".equalsIgnoreCase(current.getRole().getName()) || target.getRole()!=null && customerRoleName.equalsIgnoreCase(target.getRole().getName()));
        } catch(ResponseStatusException invalid){return false;}
    }
    @Transactional(readOnly=true)
    public List<UserAccountEvent> history(long id){
        if(!access.can("USER_ACCOUNT_EVENTS"))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Không có quyền xem lịch sử trạng thái tài khoản");
        long actor=actorId();var current=users.findById(actor).orElseThrow(()->new ResponseStatusException(HttpStatus.FORBIDDEN,"Tài khoản quản trị không còn tồn tại"));
        var target=users.findById(id).orElse(null);
        boolean admin=current.getRole()!=null && "admin".equalsIgnoreCase(current.getRole().getName());
        if(!admin && (target==null || target.getRole()==null || !customerRoleName.equalsIgnoreCase(target.getRole().getName())))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Nhân viên chỉ được xem lịch sử tài khoản khách hàng");
        return events.findByTargetUserIdOrderByIdDesc(id,org.springframework.data.domain.PageRequest.of(0,100));
    }
}
