package com.example.auth_service.service;

import com.example.auth_service.dto.request.*;
import com.example.auth_service.entity.*;
import com.example.auth_service.mapper.UserMapper;
import com.example.auth_service.repository.*;
import com.example.auth_service.repository.httpclient.FileServiceClient;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.*;
import org.junit.jupiter.api.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TokenRefreshTest {
    private static final String KEY = "test".repeat(16);
    private final UserRepository users = mock(UserRepository.class);
    private final InvalidatedTokenRepository invalidated = mock(InvalidatedTokenRepository.class);
    private final UserAccountEventRepository events = mock(UserAccountEventRepository.class);
    private final SessionEpochService sessionEpochService = mock(SessionEpochService.class);
    private AuthService service;
    private User user;
    @BeforeEach void setup() {
        service = new AuthService(users, invalidated, mock(UserMapper.class), mock(PasswordEncoder.class), mock(FileServiceClient.class), mock(RoleRepository.class), events, sessionEpochService);
        ReflectionTestUtils.setField(service, "SIGNER_KEY", KEY);
        ReflectionTestUtils.setField(service, "VALID_DURATION", 3600L);
        ReflectionTestUtils.setField(service, "REFRESHABLE_DURATION", 7200L);
        var role = new Role(); role.setName("Customer"); role.setActive(true); role.setPermissions(List.of());
        user = User.builder().id(2L).email("user@example.com").role(role).build();
    }
    private String token(String type) throws Exception {
        long lifetimeMillis = "refresh".equals(type) ? 7_200_000L : 3_600_000L;
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), new JWTClaimsSet.Builder().subject("2")
                .issuer("tour.com").issueTime(new Date()).expirationTime(new Date(System.currentTimeMillis()+lifetimeMillis))
                .jwtID("old-id").claim("tokenType", type).claim("scope", "ROLE_Customer")
                .claim("credentialVersion", com.example.auth_service.config.CredentialVersion.of(user, KEY)).build());
        jwt.sign(new MACSigner(KEY)); return jwt.serialize();
    }
    private String refreshToken() throws Exception {
        return token("refresh");
    }
    private String token(String type, String jti, Long sessionEpoch) throws Exception {
        long lifetimeMillis = "refresh".equals(type) ? 7_200_000L : 3_600_000L;
        var claims = new JWTClaimsSet.Builder().subject("2")
                .issuer("tour.com").issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + lifetimeMillis))
                .jwtID(jti).claim("tokenType", type).claim("scope", "ROLE_Customer")
                .claim("credentialVersion", com.example.auth_service.config.CredentialVersion.of(user, KEY));
        if (sessionEpoch != null) claims.claim("sessionEpoch", sessionEpoch);
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), claims.build());
        jwt.sign(new MACSigner(KEY));
        return jwt.serialize();
    }
    @Test void refreshLooksUpSubjectAsIdAndKeepsCorrectExpiryUnits() throws Exception {
        when(users.findById(2L)).thenReturn(Optional.of(user));
        var request = new RefreshRequest(); request.setToken(refreshToken());
        var response = service.refreshToken(request);
        assertEquals("2", SignedJWT.parse(response.getToken()).getJWTClaimsSet().getSubject());
        assertTrue(response.getExpiryTime().after(new Date(System.currentTimeMillis()+3500000)));
        assertNotNull(response.getRefreshToken());
        verify(users, never()).findByEmail(any());
        verify(invalidated).saveAndFlush(argThat(old -> "old-id".equals(old.getId())
                && old.getExpiryTime().after(new Date(System.currentTimeMillis()+7100000))));
    }
    @Test void replayingAConsumedRefreshTokenRevokesTheWholeFamily() throws Exception {
        var epoch = new java.util.concurrent.atomic.AtomicLong();
        when(events.sessionEpoch(2L)).thenAnswer(invocation -> epoch.get());
        doAnswer(invocation -> { epoch.incrementAndGet(); return null; })
                .when(sessionEpochService).revokeFamily(eq(2L), anyLong());
        when(users.findById(2L)).thenReturn(Optional.of(user));
        when(invalidated.existsById("replayed-id")).thenReturn(false, true);

        var request = new RefreshRequest(); request.setToken(token("refresh", "replayed-id", 0L));
        var rotated = service.refreshToken(request);
        assertNotNull(rotated.getRefreshToken());

        // An access token from the same session is still usable before the replay.
        var sibling = new IntrospectRequest(); sibling.setToken(token("access", "sibling-id", 0L));
        assertTrue(service.introspect(sibling).isValid());

        var replay = assertThrows(com.example.auth_service.exception.AppException.class,
                () -> service.refreshToken(request));
        assertEquals(com.example.auth_service.exception.ErrorCode.UNAUTHENTICATED, replay.getErrorCode());
        assertEquals(1L, epoch.get());
        verify(sessionEpochService).revokeFamily(2L, 0L);
        // Bumping the epoch invalidates the sibling access token and every other family member.
        assertFalse(service.introspect(sibling).isValid());

        // A normal, unconsumed rotation at the new epoch still works.
        var fresh = new RefreshRequest(); fresh.setToken(token("refresh", "fresh-id", epoch.get()));
        var next = service.refreshToken(fresh);
        assertNotNull(next.getRefreshToken());
        assertEquals(1L, epoch.get());
    }
    @Test void accessTokenCannotRefreshAndRefreshTokenCannotCallApi() throws Exception {
        when(users.findById(2L)).thenReturn(Optional.of(user));
        var access = new RefreshRequest(); access.setToken(token("access"));
        assertThrows(com.example.auth_service.exception.AppException.class, () -> service.refreshToken(access));
        var introspect = new IntrospectRequest(); introspect.setToken(refreshToken());
        assertFalse(service.introspect(introspect).isValid());
    }
    @Test void failedRefreshDoesNotConsumeValidToken() throws Exception {
        when(users.findById(2L)).thenReturn(Optional.empty());
        var request = new RefreshRequest(); request.setToken(refreshToken());
        assertThrows(com.example.auth_service.exception.AppException.class, () -> service.refreshToken(request));
        verify(invalidated, never()).save(any());
    }
    @Test void malformedRefreshTokenIsAnInvalidCredentialNotAServerFailure() {
        for (String malformed : new String[]{"not-a-jwt", "a.b.c", "x".repeat(16385), ""}) {
            var request = new RefreshRequest(); request.setToken(malformed);
            var failure = assertThrows(com.example.auth_service.exception.AppException.class,
                    () -> service.refreshToken(request));
            assertEquals(com.example.auth_service.exception.ErrorCode.INVALID_TOKEN, failure.getErrorCode());
        }
        // A damaged token must never reach persistence nor revoke a session family.
        verifyNoInteractions(invalidated, sessionEpochService, users);
    }
    @Test void malformedIdentityInChangePasswordIsRejectedBeforeTouchingTheAccount() {
        var request = ChangePasswordRequest.builder().oldPassword("old-password")
                .newPassword("new-password").confirmPassword("new-password").build();
        var failure = assertThrows(com.example.auth_service.exception.AppException.class,
                () -> service.changePassword("not-a-subject", "binding", request));
        assertEquals(com.example.auth_service.exception.ErrorCode.INVALID_TOKEN, failure.getErrorCode());
        verifyNoInteractions(users);
    }
    @Test void inactiveRoleCannotRefreshOrPassIntrospection() throws Exception {
        user.getRole().setActive(false); when(users.findById(2L)).thenReturn(Optional.of(user));
        var request = new RefreshRequest(); request.setToken(refreshToken());
        assertThrows(com.example.auth_service.exception.AppException.class, () -> service.refreshToken(request));
        var introspect = new IntrospectRequest(); introspect.setToken(request.getToken());
        assertFalse(service.introspect(introspect).isValid());
    }
    @Test void googleRegistrationUsesCustomerRoleAndAnUnpredictableLocalPassword() {
        var roles = (RoleRepository) ReflectionTestUtils.getField(service, "roleRepository");
        var encoder = (PasswordEncoder) ReflectionTestUtils.getField(service, "passwordEncoder");
        ReflectionTestUtils.setField(service, "customerRoleName", "Customer");
        when(roles.findByName("Customer")).thenReturn(user.getRole());
        when(users.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(encoder.encode(any())).thenReturn("encoded-random-value");
        var registered = service.findOrCreateGoogleUser("google-id", "other@example.com", "Display name", null);
        assertSame(user.getRole(), registered.getRole());
        verify(encoder).encode(argThat(value -> value != null && !"Display name".contentEquals(value) && value.length() >= 32));
    }

    @Test void googleUsernameFitsTheUniqueColumnAndGetsASuffixWhenTaken() {
        var roles = (RoleRepository) ReflectionTestUtils.getField(service, "roleRepository");
        ReflectionTestUtils.setField(service, "customerRoleName", "Customer");
        when(roles.findByName("Customer")).thenReturn(user.getRole());
        when(users.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var longName = "Nguyễn Văn Khách Hàng Rất Dài ".repeat(4);
        var longRegistered = service.findOrCreateGoogleUser("google-long", "long@example.com", longName, null);
        assertTrue(longRegistered.getUsername().length() <= 50);
        assertFalse(longRegistered.getUsername().isBlank());

        when(users.existsByUsername(anyString())).thenReturn(true);
        var duplicate = service.findOrCreateGoogleUser("google-dupe", "dupe@example.com", "Display name", null);
        assertTrue(duplicate.getUsername().startsWith("Display name-"));
        assertTrue(duplicate.getUsername().length() <= 50);

        var blank = service.findOrCreateGoogleUser("google-blank", "blank.user@example.com", "   ", null);
        assertTrue(blank.getUsername().startsWith("blank.user-"));
    }
    @Test void renamingPermissionLabelsDoesNotChangeAuthoritiesOrInjectAdminScope() throws Exception {
        var permission = new Permission("TOURS_UPDATE", "Cập nhật tour", "/api/v1/tour/tours/{id}", "PUT", "TOURS");
        user.getRole().setPermissions(List.of(permission)); when(users.findById(2L)).thenReturn(Optional.of(user));
        var first = new RefreshRequest(); first.setToken(refreshToken());
        var refreshed = service.refreshToken(first);
        String issued = refreshed.getToken();
        String original = (String) SignedJWT.parse(issued).getJWTClaimsSet().getClaim("scope");
        permission.setName("New label ACCESS_ADMIN");
        var next = new RefreshRequest(); next.setToken(refreshed.getRefreshToken());
        String renamed = (String) SignedJWT.parse(service.refreshToken(next).getToken()).getJWTClaimsSet().getClaim("scope");
        assertEquals(original, renamed);
        assertTrue(renamed.contains("PERM:TOURS_UPDATE"));
        assertFalse(Arrays.asList(renamed.split(" ")).contains("SGU_SUPER_ADMIN"));
    }
    @Test void onlyTheActiveDatabaseAdminRoleReceivesSuperAdminAuthority() {
        user.getRole().setPermissions(List.of(new Permission("ADMIN", "/ui/admin", "GET", "UI")));
        String staff = ReflectionTestUtils.invokeMethod(service,"buildScope",user);
        assertFalse(staff.contains("SGU_SUPER_ADMIN"));
        user.getRole().setName("admin");
        String admin = ReflectionTestUtils.invokeMethod(service,"buildScope",user);
        assertTrue(admin.contains("SGU_SUPER_ADMIN"));
        user.getRole().setActive(false);
        String inactive = ReflectionTestUtils.invokeMethod(service,"buildScope",user);
        assertFalse(inactive.contains("SGU_SUPER_ADMIN"));
    }
}
