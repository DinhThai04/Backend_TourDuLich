package com.example.auth_service.service;

import com.example.auth_service.config.CredentialVersion;
import com.example.auth_service.dto.request.*;
import com.example.auth_service.entity.*;
import com.example.auth_service.repository.*;
import com.example.auth_service.repository.httpclient.FileServiceClient;
import com.example.auth_service.mapper.UserMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class PasswordChangeSecurityTest {
    static final String KEY="test".repeat(16);
    final UserRepository users=mock(UserRepository.class);
    final InvalidatedTokenRepository invalidated=mock(InvalidatedTokenRepository.class);
    final BCryptPasswordEncoder encoder=new BCryptPasswordEncoder(4);
    AuthService service; User user;
    @BeforeEach void setup() {
        service=new AuthService(users,invalidated,mock(UserMapper.class),encoder,mock(FileServiceClient.class),mock(RoleRepository.class),mock(UserAccountEventRepository.class),mock(SessionEpochService.class));
        ReflectionTestUtils.setField(service,"SIGNER_KEY",KEY);
        ReflectionTestUtils.setField(service,"VALID_DURATION",3600L);ReflectionTestUtils.setField(service,"REFRESHABLE_DURATION",7200L);
        var role=new Role();role.setName("Customer");role.setActive(true);role.setPermissions(List.of());
        user=User.builder().id(2L).role(role).password(encoder.encode("OldPassword123")).build();
        when(users.findById(2L)).thenReturn(Optional.of(user));when(users.lockForPasswordChange(2L)).thenReturn(Optional.of(user));
    }
    String token(boolean bound) throws Exception {
        var claims=new JWTClaimsSet.Builder().subject("2").issuer("tour.com").issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis()+3600000)).jwtID(UUID.randomUUID().toString()).claim("scope","ROLE_Customer");
        if(bound)claims.claim("credentialVersion",CredentialVersion.of(user,KEY));
        var jwt=new SignedJWT(new JWSHeader(JWSAlgorithm.HS512),claims.build());jwt.sign(new MACSigner(KEY));return jwt.serialize();
    }
    String refreshToken() throws Exception {
        var claims=new JWTClaimsSet.Builder().subject("2").issuer("tour.com").issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis()+7200000)).jwtID(UUID.randomUUID().toString())
                .claim("tokenType","refresh").claim("credentialVersion",CredentialVersion.of(user,KEY));
        var jwt=new SignedJWT(new JWSHeader(JWSAlgorithm.HS512),claims.build());jwt.sign(new MACSigner(KEY));return jwt.serialize();
    }
    ChangePasswordRequest request(String password) {
        return ChangePasswordRequest.builder().oldPassword("OldPassword123").newPassword(password).confirmPassword(password).build();
    }
    String binding() { return CredentialVersion.of(user,KEY); }
    void changePassword(String password) { service.changePassword("2",binding(),request(password)); }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"a","       ","1234567"})
    void invalidPasswordNeverWrites(String password) throws Exception {
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->changePassword(password));
        verify(users,never()).save(any());
    }
    @Test void bcryptByteLimitIsEnforcedForUnicode() throws Exception {
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->changePassword("ệ".repeat(25)));
        verify(users,never()).save(any());
    }
    @Test void changeInvalidatesEveryPreviouslyIssuedTokenAndRefresh() throws Exception {
        String first=token(true),second=token(true),refreshBefore=refreshToken();
        var introspect=new IntrospectRequest();introspect.setToken(second);assertTrue(service.introspect(introspect).isValid());
        changePassword("NewPassword123");assertTrue(encoder.matches("NewPassword123",user.getPassword()));
        assertFalse(service.introspect(introspect).isValid());introspect.setToken(first);assertFalse(service.introspect(introspect).isValid());
        var refresh=new RefreshRequest();refresh.setToken(refreshBefore);assertThrows(com.example.auth_service.exception.AppException.class,()->service.refreshToken(refresh));
        introspect.setToken(token(true));assertTrue(service.introspect(introspect).isValid());verify(users).save(user);
    }
    @Test void legacyUnboundTokenCannotBeRefreshed() throws Exception {
        var introspect=new IntrospectRequest();introspect.setToken(token(false));assertFalse(service.introspect(introspect).isValid());
    }
    @Test void wrongOldPasswordAndConfirmationNeverWrite() throws Exception {
        var req=request("NewPassword123");req.setOldPassword("WrongPassword");assertThrows(com.example.auth_service.exception.AppException.class,()->service.changePassword("2",binding(),req));
        req.setOldPassword("OldPassword123");req.setConfirmPassword("OtherPassword123");assertThrows(com.example.auth_service.exception.AppException.class,()->service.changePassword("2",binding(),req));verify(users,never()).save(any());
    }
    @Test void staleBindingCannotChangeAnAlreadyRotatedPassword() {
        var req=request("NewPassword123");
        assertThrows(com.example.auth_service.exception.AppException.class,
                ()->service.changePassword("2","stale-binding",req));
        verify(users,never()).save(any());
    }
}
