package com.example.auth_service.service;

import com.example.auth_service.config.*;
import com.example.auth_service.entity.*;
import com.example.auth_service.repository.*;
import com.example.auth_service.repository.httpclient.FileServiceClient;
import com.example.auth_service.mapper.UserMapper;
import com.example.auth_service.dto.request.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({AccountLifecycleService.class,AccountAccess.class,AdminContinuity.class,UserService.class,RoleService.class,PermissionService.class,SessionEpochService.class})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class AccountLifecycleTest {
    @DynamicPropertySource static void db(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->"jdbc:h2:mem:account_lifecycle;MODE=MySQL;DB_CLOSE_DELAY=-1");r.add("spring.datasource.driver-class-name",()->"org.h2.Driver");r.add("spring.datasource.username",()->"sa");r.add("spring.datasource.password",()->"");}
    @Autowired AccountLifecycleService service;
    @Autowired UserService userService;
    @Autowired RoleService roleService;
    @Autowired PermissionService permissionService;
    @org.springframework.boot.test.mock.mockito.MockBean UserMapper userMapper;
    @org.springframework.boot.test.mock.mockito.MockBean org.springframework.security.crypto.password.PasswordEncoder encoder;
    @org.springframework.boot.test.mock.mockito.MockBean FileServiceClient files;
    @org.springframework.boot.test.mock.mockito.MockBean AvatarProfileWriter avatarProfileWriter;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PermissionRepository permissions;
    @Autowired UserAccountEventRepository events;
    @Autowired InvalidatedTokenRepository tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    boolean valid(AuthService auth,IntrospectRequest request){return new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(tx->auth.introspect(request).isValid());}
    User admin,customer; Role customerRole;
    @Test void soleAdminCannotDemoteItself() {
        var request=new UserUpdate();request.setRoleId(customerRole.getId());
        var failure=assertThrows(org.springframework.web.server.ResponseStatusException.class,()->userService.updateUser(admin.getId(),request));
        assertEquals(409,failure.getStatusCode().value());
        assertEquals(admin.getRole().getId(),users.findById(admin.getId()).orElseThrow().getRole().getId());
    }
    @Test void adminRoleCannotBeDisabledRenamedOrDeleted() {
        var changed=new Role();changed.setId(admin.getRole().getId());changed.setName("admin");changed.setActive(false);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->roleService.update(changed));
        changed.setActive(true);changed.setName("staff");
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->roleService.update(changed));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->roleService.delete(changed.getId()));
        var stored=roles.findById(changed.getId()).orElseThrow();assertTrue(Boolean.TRUE.equals(stored.getActive()));assertEquals("admin",stored.getName());
    }
    @Test void multipleAdminsOnSameRoleDoNotAllowDisablingThatRole() {
        users.save(User.builder().username("backup").email("backup@test.invalid").password("hash").role(admin.getRole()).build());
        var changed=new Role();changed.setId(admin.getRole().getId());changed.setName("admin");changed.setActive(false);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->roleService.update(changed));
    }
    @Test void lockedBackupIsNotAnActiveAdmin() {
        users.save(User.builder().username("backup").email("backup@test.invalid").password("hash").role(admin.getRole()).status(UserStatus.LOCKED).build());
        var request=new UserUpdate();request.setRoleId(customerRole.getId());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->userService.updateUser(admin.getId(),request));
    }
    @Test void assignedNonAdminRoleDeletionReturnsConflict() {
        var failure=assertThrows(org.springframework.web.server.ResponseStatusException.class,()->roleService.delete(customerRole.getId()));
        assertEquals(409,failure.getStatusCode().value());assertTrue(roles.existsById(customerRole.getId()));
    }
    @Test void roleDisableAndDemotionShareTheSameWriteFence() throws Exception {
        var otherRole=new Role();otherRole.setName("ADMIN");otherRole.setActive(true);otherRole=roles.save(otherRole);
        var backup=users.save(User.builder().username("backup").email("backup@test.invalid").password("hash").role(otherRole).build());
        var changed=new Role();changed.setId(admin.getRole().getId());changed.setName("admin");changed.setActive(false);
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var roleChange=pool.submit(()->{
                start.await();try {roleService.update(changed);return true;}
                catch(org.springframework.web.server.ResponseStatusException rejected){assertEquals(409,rejected.getStatusCode().value());return false;}
            });
            var demotion=pool.submit(()->{
                login(backup.getId());start.await();var request=new UserUpdate();request.setRoleId(customerRole.getId());
                try {userService.updateUser(backup.getId(),request);return true;}
                catch(org.springframework.web.server.ResponseStatusException rejected){assertEquals(409,rejected.getStatusCode().value());return false;}
                finally {SecurityContextHolder.clearContext();}
            });
            start.countDown();assertNotEquals(roleChange.get(20,TimeUnit.SECONDS),demotion.get(20,TimeUnit.SECONDS));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM users u JOIN roles r ON r.id=u.role_id WHERE r.active=TRUE AND u.status='ACTIVE' AND LOWER(r.name)='admin'",Integer.class));
        } finally {pool.shutdownNow();}
    }
    @Test void demotionIsAllowedWithAnActiveBackup() {
        users.save(User.builder().username("backup").email("backup@test.invalid").password("hash").role(admin.getRole()).build());
        var request=new UserUpdate();request.setRoleId(customerRole.getId());userService.updateUser(admin.getId(),request);
        assertEquals(customerRole.getId(),users.findById(admin.getId()).orElseThrow().getRole().getId());
    }
    @Test void concurrentDemotionsLeaveOneActiveAdmin() throws Exception {
        var backup=users.save(User.builder().username("backup").email("backup@test.invalid").password("hash").role(admin.getRole()).build());
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            List<Future<Boolean>> outcomes=new ArrayList<>();
            for(long id:List.of(admin.getId(),backup.getId())) outcomes.add(pool.submit(()->{
                login(id);start.await();var request=new UserUpdate();request.setRoleId(customerRole.getId());
                try {userService.updateUser(id,request);return true;}
                catch(org.springframework.web.server.ResponseStatusException rejected){assertEquals(409,rejected.getStatusCode().value());return false;}
                finally {SecurityContextHolder.clearContext();}
            }));
            start.countDown();int succeeded=0;for(var result:outcomes)if(result.get(20,TimeUnit.SECONDS))succeeded++;
            assertEquals(1,succeeded);
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE role_id=? AND status='ACTIVE'",Integer.class,admin.getRole().getId()));
        } finally {pool.shutdownNow();}
    }
    @Test void staleProfileSaveCannotUndoAnAccountLock() {
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status->{
            var stale=users.findById(customer.getId()).orElseThrow();
            var inner=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
            inner.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            inner.executeWithoutResult(ignored->service.status(customer.getId(),UserStatus.LOCKED,"lock during profile update"));
            stale.setPhone("0901234567");users.saveAndFlush(stale);
        });
        assertEquals(UserStatus.LOCKED,users.findById(customer.getId()).orElseThrow().getStatus());
    }
    @BeforeEach void setup(){
        for(String ddl:List.of("booking(id INT PRIMARY KEY,account_id BIGINT,tour_departure_id INT,status VARCHAR(30))",
            "tour_departure(id INT PRIMARY KEY,return_date TIMESTAMP)","vnpay_transaction(account_id BIGINT,status VARCHAR(30),expires_at TIMESTAMP)",
            "momo_transaction(account_id BIGINT,status VARCHAR(30),expires_at TIMESTAMP)",
            "payment_reconciliation(booking_id INT,status VARCHAR(30))","booking_change(account_id BIGINT,active_key INT)",
            "payment(account_id BIGINT)","invoice(account_id BIGINT)","booking_change_event(actor_id BIGINT)",
            "departure_change(created_by BIGINT)","mail_outbox(account_id BIGINT)","payment_reconciliation_event(actor_id BIGINT)",
            "avatar_media_lock(owner_id VARCHAR(19) PRIMARY KEY)","file_mgmt(id VARCHAR(36) PRIMARY KEY,owner_id VARCHAR(255),tour_id VARCHAR(255),avatar_owner_id VARCHAR(19) UNIQUE,location_id INT)")) jdbc.execute("CREATE TABLE IF NOT EXISTS "+ddl);
        for(String table:List.of("booking","tour_departure","vnpay_transaction","momo_transaction","payment_reconciliation","booking_change","payment","invoice","booking_change_event","departure_change","mail_outbox","payment_reconciliation_event","file_mgmt"))jdbc.update("DELETE FROM "+table);
        events.deleteAll();tokens.deleteAll();users.deleteAll();roles.deleteAll();permissions.deleteAll();
        Role adminRole=new Role();adminRole.setName("admin");adminRole.setActive(true);adminRole.setPermissions(new ArrayList<>());adminRole=roles.save(adminRole);
        customerRole=new Role();customerRole.setName("Khách Hàng");customerRole.setActive(true);customerRole.setPermissions(new ArrayList<>());customerRole=roles.save(customerRole);
        admin=users.save(User.builder().username("admin-test").email("admin@test.invalid").password("hash-admin").role(adminRole).build());
        customer=users.save(User.builder().username("customer-test").email("customer@test.invalid").password("hash-user").role(customerRole).build());
        login(admin.getId());
    }
    void login(long id){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(Long.toString(id),"",List.of()));}
    @AfterEach void clean(){SecurityContextHolder.clearContext();}
    @Test void lockUnlockAuditsAndCannotReactivateAnOldSession() throws Exception {
        String key="test".repeat(16);
        var jwt=new SignedJWT(new JWSHeader(JWSAlgorithm.HS512),new JWTClaimsSet.Builder().subject(customer.getId().toString()).issuer("tour.com").issueTime(new Date()).expirationTime(new Date(System.currentTimeMillis()+3600000)).jwtID("old-customer-token").claim("scope","ROLE_Khách_Hàng").claim("credentialVersion",CredentialVersion.of(customer,key)).build());jwt.sign(new MACSigner(key));
        var auth=new AuthService(users,tokens,mock(UserMapper.class),mock(org.springframework.security.crypto.password.PasswordEncoder.class),mock(FileServiceClient.class),roles,events,mock(SessionEpochService.class));
        ReflectionTestUtils.setField(auth,"SIGNER_KEY",key);ReflectionTestUtils.setField(auth,"VALID_DURATION",3600L);ReflectionTestUtils.setField(auth,"REFRESHABLE_DURATION",7200L);
        var introspect=new IntrospectRequest();introspect.setToken(jwt.serialize());assertTrue(valid(auth,introspect));
        service.status(customer.getId(),UserStatus.LOCKED,"Test lock");assertFalse(valid(auth,introspect));
        service.status(customer.getId(),UserStatus.ACTIVE,"Test unlock");assertFalse(valid(auth,introspect));
        assertEquals(UserStatus.ACTIVE,users.findById(customer.getId()).orElseThrow().getStatus());assertEquals(2,events.count());
        String fresh=new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(tx->ReflectionTestUtils.invokeMethod(auth,"generateToken",users.findById(customer.getId()).orElseThrow(),AuthService.TokenType.ACCESS));
        var current=new IntrospectRequest();current.setToken(fresh);assertTrue(valid(auth,current));
        var refresh=new RefreshRequest();refresh.setToken(jwt.serialize());assertThrows(com.example.auth_service.exception.AppException.class,()->auth.refreshToken(refresh));
    }
    @Test void unassigningAPermissionFromTheRoleLogsOutItsExistingToken() throws Exception {
        var permission = new Permission("TOURS_UPDATE", "Cập nhật tour", "/api/v1/tours", "PUT", "TOUR");
        permissions.save(permission);
        // Role.permissions owns the join table, so the grant has to go through the role entity.
        customerRole.getPermissions().add(permission);
        roles.save(customerRole);
        var auth = new AuthService(users, tokens, mock(UserMapper.class),
                mock(org.springframework.security.crypto.password.PasswordEncoder.class),
                mock(FileServiceClient.class), roles, events, mock(SessionEpochService.class));
        ReflectionTestUtils.setField(auth, "SIGNER_KEY", "test".repeat(16));
        ReflectionTestUtils.setField(auth, "VALID_DURATION", 3600L);
        ReflectionTestUtils.setField(auth, "REFRESHABLE_DURATION", 7200L);
        var held = new IntrospectRequest();
        held.setToken(new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(tx -> {
            var member = users.findById(customer.getId()).orElseThrow();
            // The role and its permission list are lazy, so build the token while this session is open.
            org.hibernate.Hibernate.initialize(member.getRole().getPermissions());
            return ReflectionTestUtils.invokeMethod(auth, "generateToken", member, AuthService.TokenType.ACCESS);
        }));
        assertTrue(valid(auth, held), "vừa cấp token thì còn hiệu lực");

        var edit = new PermissionRequest();
        edit.setId(permission.getId()); edit.setCode("TOURS_UPDATE"); edit.setName("Cập nhật tour");
        edit.setApiPath("/api/v1/tours"); edit.setMethod("PUT"); edit.setModule("TOUR"); edit.setRoleIds(List.of());
        permissionService.update(edit);

        // The old token still carries PERM:TOURS_UPDATE and has not expired; only the epoch bump revokes it.
        assertFalse(valid(auth, held), "gỡ quyền khỏi vai trò thì mọi phiên cũ phải hết hiệu lực");
    }
    @Test void noSelfActionAndNoDeleteWhileActive(){
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.status(admin.getId(),UserStatus.LOCKED,"self"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.delete(customer.getId(),"active"));assertTrue(users.existsById(customer.getId()));
    }
    @Test void historicalAccountsAreArchivedWithoutDeletingBookings(){
        jdbc.update("INSERT INTO tour_departure VALUES(1,DATEADD('DAY',-1,CURRENT_TIMESTAMP))");jdbc.update("INSERT INTO booking VALUES(1,?,1,'CONFIRMED')",customer.getId());
        service.status(customer.getId(),UserStatus.LOCKED,"close");assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.delete(customer.getId(),"purge"));
        assertEquals(UserStatus.ARCHIVED,service.archive(customer.getId(),"Archive completed customer").getStatus());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM booking",Integer.class));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.status(customer.getId(),UserStatus.ACTIVE,"reopen"));
    }
    @Test void reconciliationAuditPreventsPhysicalDeletionOfItsActor() {
        jdbc.update("INSERT INTO payment_reconciliation_event(actor_id) VALUES(?)",customer.getId());
        service.status(customer.getId(),UserStatus.LOCKED,"close");
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.delete(customer.getId(),"purge"));
        assertTrue(users.existsById(customer.getId()));
        assertEquals(UserStatus.ARCHIVED,service.archive(customer.getId(),"preserve audit").getStatus());
    }
    @Test void deletingAnUnusedAccountDetachesAvatarWithoutDeletingSharedAssets() {
        String owner=customer.getId().toString();
        jdbc.update("INSERT INTO file_mgmt VALUES('avatar',?,'null',?,NULL)",owner,owner);
        jdbc.update("INSERT INTO file_mgmt VALUES('generic',?,'null',NULL,NULL)",owner);
        jdbc.update("INSERT INTO file_mgmt VALUES('tour-image',?,'1',NULL,NULL)",owner);
        service.status(customer.getId(),UserStatus.LOCKED,"close");service.delete(customer.getId(),"unused");
        assertFalse(users.existsById(customer.getId()));assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM file_mgmt",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM file_mgmt WHERE avatar_owner_id IS NOT NULL",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM file_mgmt WHERE owner_id='' AND tour_id='null'",Integer.class));
        assertEquals("1",jdbc.queryForObject("SELECT tour_id FROM file_mgmt WHERE id='tour-image'",String.class));
    }
    @Test void ongoingTourAndUnresolvedMoneyBlockArchive(){
        service.status(customer.getId(),UserStatus.LOCKED,"close");
        jdbc.update("INSERT INTO tour_departure VALUES(1,DATEADD('DAY',1,CURRENT_TIMESTAMP))");jdbc.update("INSERT INTO booking VALUES(1,?,1,'CONFIRMED')",customer.getId());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.archive(customer.getId(),"not done"));
        jdbc.update("DELETE FROM booking");jdbc.update("INSERT INTO vnpay_transaction VALUES(?,'PENDING',DATEADD('DAY',1,CURRENT_TIMESTAMP))",customer.getId());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.archive(customer.getId(),"pending"));
        jdbc.update("DELETE FROM vnpay_transaction");jdbc.update("INSERT INTO booking_change VALUES(?,1)",customer.getId());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.archive(customer.getId(),"change"));
    }
    @Test void expiredPendingPaymentsAreNotATicketOutOfArchival(){
        // Reconciliation, not the account lifecycle, owns a PENDING row past its expiry.
        service.status(customer.getId(),UserStatus.LOCKED,"close");
        jdbc.update("INSERT INTO vnpay_transaction VALUES(?,'PENDING',DATEADD('HOUR',-1,CURRENT_TIMESTAMP))",customer.getId());
        assertEquals(UserStatus.ARCHIVED,service.archive(customer.getId(),"expired holds stay reconcilable").getStatus());
    }
    @Test void liveMoMoHoldIsGuardedLikeVNPay(){
        service.status(customer.getId(),UserStatus.LOCKED,"close");
        jdbc.update("INSERT INTO momo_transaction VALUES(?,'PENDING',DATEADD('DAY',1,CURRENT_TIMESTAMP))",customer.getId());
        var blocked=assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.archive(customer.getId(),"momo pending"));
        assertEquals(409,blocked.getStatusCode().value());
        jdbc.update("DELETE FROM momo_transaction");
        jdbc.update("INSERT INTO momo_transaction VALUES(?,'CLOSED_UNPAID',DATEADD('HOUR',-1,CURRENT_TIMESTAMP))",customer.getId());
        assertEquals(UserStatus.ARCHIVED,service.archive(customer.getId(),"settled momo order").getStatus());
    }
    @Test void neverUsedLockedAccountCanBeDeletedButItsAuditSurvives(){
        service.status(customer.getId(),UserStatus.LOCKED,"unused");service.delete(customer.getId(),"Remove unused account");
        assertFalse(users.existsById(customer.getId()));assertEquals(2,events.count());assertTrue(events.sessionEpoch(customer.getId())>0);
        assertEquals(2,service.history(customer.getId()).size());
    }
    @Test void delegatedStaffCanManageCustomersButNotOtherStaff(){
        var right=permissions.save(new Permission("USER_STATUS_UPDATE","Cập nhật trạng thái","/api/v1/auth/users/{id}/status","PUT","USERS"));
        var role=new Role();role.setName("staff");role.setActive(true);role.setPermissions(new ArrayList<>(List.of(right)));role=roles.save(role);
        var staff=users.save(User.builder().username("staff-test").email("staff@test.invalid").password("hash").role(role).build());
        var colleague=users.save(User.builder().username("colleague").email("colleague@test.invalid").password("hash").role(role).build());
        login(staff.getId());assertEquals(UserStatus.LOCKED,service.status(customer.getId(),UserStatus.LOCKED,"Support action").getStatus());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.status(colleague.getId(),UserStatus.LOCKED,"Not allowed"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.status(admin.getId(),UserStatus.LOCKED,"Not allowed"));
    }
    @Test void twoAdminsCannotDisableEachOtherAndLeaveZeroActiveAdmins() throws Exception {
        var second=users.save(User.builder().username("admin-two").email("two@test.invalid").password("hash").role(admin.getRole()).build());
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var futures=new ArrayList<Future<Boolean>>();
            for(long[] pair:new long[][]{{admin.getId(),second.getId()},{second.getId(),admin.getId()}})futures.add(pool.submit(()->{
                login(pair[0]);start.await();try {service.status(pair[1],UserStatus.LOCKED,"Concurrent admin action");return true;}
                catch(org.springframework.web.server.ResponseStatusException denied){return false;}finally{SecurityContextHolder.clearContext();}
            }));start.countDown();int successes=0;for(var future:futures)if(future.get(10,TimeUnit.SECONDS))successes++;
            assertEquals(1,successes);assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM users u JOIN roles r ON r.id=u.role_id WHERE r.name='admin' AND u.status='ACTIVE'",Integer.class));
        }finally{pool.shutdownNow();}
    }
    @Test void lockedAccountsCannotLoginOrLinkGoogleAndInvalidReasonDoesNotWrite() {
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.status(customer.getId(),UserStatus.LOCKED,"  "));
        assertEquals(0,events.count());
        var encoder=new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4);
        var record=users.findById(customer.getId()).orElseThrow();record.setPassword(encoder.encode("UserPassword123"));users.save(record);
        service.status(customer.getId(),UserStatus.LOCKED,"Security check");
        var auth=new AuthService(users,tokens,mock(UserMapper.class),encoder,mock(FileServiceClient.class),roles,events,mock(SessionEpochService.class));
        ReflectionTestUtils.setField(auth,"SIGNER_KEY","test".repeat(16));ReflectionTestUtils.setField(auth,"VALID_DURATION",3600L);
        var request=new AuthenticationRequest();request.setEmail(customer.getEmail());request.setPassword("UserPassword123");
        var error=assertThrows(com.example.auth_service.exception.AppException.class,()->auth.authenticated(request));assertEquals(com.example.auth_service.exception.ErrorCode.BAD_CREDENTIALS,error.getErrorCode());
        assertThrows(com.example.auth_service.exception.AppException.class,()->auth.findOrCreateGoogleUser("new-google-id",customer.getEmail(),"Name",null));
        assertNull(users.findById(customer.getId()).orElseThrow().getGoogleId());
    }
}
