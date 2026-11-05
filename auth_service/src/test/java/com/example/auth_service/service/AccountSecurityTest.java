package com.example.auth_service.service;

import com.example.auth_service.config.AccountAccess;
import com.example.auth_service.dto.request.ReviewRequest;
import com.example.auth_service.dto.request.UserUpdate;
import com.example.auth_service.entity.*;
import com.example.auth_service.mapper.*;
import com.example.auth_service.repository.*;
import com.example.auth_service.repository.httpclient.FileServiceClient;
import com.corundumstudio.socketio.SocketIOServer;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.validation.Validation;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountSecurityTest {
    @AfterEach void clean() { SecurityContextHolder.clearContext(); }
    private void login(long id) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(String.valueOf(id), "", List.of()));
    }
    @Test void reviewUsesAuthenticatedAuthorAndNeverClientIdentity() {
        login(2);
        var users = mock(UserRepository.class);
        var reviews = mock(ReviewRepository.class);
        var mapper = mock(ReviewMapper.class);
        var sockets = mock(SocketIOServer.class, RETURNS_DEEP_STUBS);
        var author = User.builder().id(2L).username("actual").build();
        when(users.lockForReview(2L)).thenReturn(Optional.of(author));
        var eligibility = mock(ReviewEligibility.class);
        when(eligibility.check(1L)).thenReturn(new com.example.auth_service.repository.httpclient.BookingReviewClient.Eligibility(true,"ok"));
        var service = new ReviewService(reviews, mapper, sockets, users, eligibility,
                mock(com.example.auth_service.repository.httpclient.TourCatalogClient.class),
                new TransactionTemplate(mock(PlatformTransactionManager.class)));
        var request = new ReviewRequest();
        request.setTourId(1L);
        request.setContent("  Tốt  "); request.setRating(5);
        service.createReview(request);
        verify(reviews).save(argThat(r -> r.getUser() == author && "Tốt".equals(r.getContent())));
        verify(users, never()).findById(1L);
    }
    @Test void invalidRatingsAndEmptyContentAreRejected() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = new ReviewRequest(); request.setTourId(1L); request.setContent(" "); request.setRating(6);
            assertEquals(2, factory.getValidator().validate(request).size());
            request.setContent("Tốt"); request.setRating(1);
            assertTrue(factory.getValidator().validate(request).isEmpty());
        }
    }
    @Test void customerCannotPromoteOwnRole() {
        login(2);
        var users = mock(UserRepository.class);
        var roles = mock(RoleRepository.class);
        var role = new Role(); role.setId(2L);
        var user = User.builder().id(2L).role(role).build();
        when(users.lockForLifecycle(2L)).thenReturn(Optional.of(user));
        var access = mock(AccountAccess.class);
        var service = new UserService(mock(com.example.auth_service.service.AccountLifecycleService.class), users, mock(UserMapper.class), mock(PasswordEncoder.class), mock(FileServiceClient.class), roles, access, mock(AdminContinuity.class), mock(AvatarProfileWriter.class), mock(SessionEpochService.class));
        var request = new UserUpdate(); request.setRoleId(1L);
        assertThrows(AccessDeniedException.class, () -> service.updateUser(2L, request));
        verify(users, never()).save(any()); verifyNoInteractions(roles);
    }
    @Test void changingTheRoleRevokesTheAccountsSessions() {
        login(5);
        var users = mock(UserRepository.class);
        var roles = mock(RoleRepository.class);
        var oldRole = new Role(); oldRole.setId(2L);
        var newRole = new Role(); newRole.setId(3L); newRole.setActive(true);
        var user = User.builder().id(5L).role(oldRole).build();
        when(users.lockForLifecycle(5L)).thenReturn(Optional.of(user));
        when(roles.findById(3L)).thenReturn(Optional.of(newRole));
        var access = mock(AccountAccess.class);
        when(access.can("ROLES_UPDATE")).thenReturn(true);
        var epochs = mock(SessionEpochService.class);
        var service = new UserService(mock(com.example.auth_service.service.AccountLifecycleService.class), users, mock(UserMapper.class), mock(PasswordEncoder.class), mock(FileServiceClient.class), roles, access, mock(AdminContinuity.class), mock(AvatarProfileWriter.class), epochs);
        var request = new UserUpdate(); request.setRoleId(3L);
        service.updateUser(5L, request);
        verify(epochs).revokeAll(eq(List.of(5L)), contains("Vai trò"));
    }
    @Test void keepingTheSameRoleDoesNotRevokeSessions() {
        login(5);
        var users = mock(UserRepository.class);
        var roles = mock(RoleRepository.class);
        var role = new Role(); role.setId(2L); role.setActive(true);
        var user = User.builder().id(5L).role(role).build();
        when(users.lockForLifecycle(5L)).thenReturn(Optional.of(user));
        when(roles.findById(2L)).thenReturn(Optional.of(role));
        var epochs = mock(SessionEpochService.class);
        var service = new UserService(mock(com.example.auth_service.service.AccountLifecycleService.class), users, mock(UserMapper.class), mock(PasswordEncoder.class), mock(FileServiceClient.class), roles, mock(AccountAccess.class), mock(AdminContinuity.class), mock(AvatarProfileWriter.class), epochs);
        var request = new UserUpdate(); request.setRoleId(2L);
        service.updateUser(5L, request);
        verify(epochs, never()).revokeAll(any(), anyString());
    }
    @Test void databasePermissionChecksRejectMissingAndInactiveRoles() {
        login(2);
        var users = mock(UserRepository.class);
        var role = new Role(); role.setId(2L); role.setActive(false);
        role.setPermissions(List.of(new Permission("UI_ENTRY", "Lối vào quản trị", "/ui/admin", "GET", "UI")));
        when(users.findById(2L)).thenReturn(Optional.of(User.builder().id(2L).role(role).build()));
        var access = new AccountAccess(users);
        assertFalse(access.can("USERS_DELETE"));
        role.setActive(true);
        assertFalse(access.can("USERS_DELETE"));
        assertTrue(access.owns(2L)); assertFalse(access.owns(1L));
    }
}
