package com.example.auth_service.service;

import com.example.auth_service.entity.Role;
import com.example.auth_service.entity.User;
import com.example.auth_service.entity.UserStatus;
import com.example.auth_service.exception.AppException;
import com.example.auth_service.exception.ErrorCode;
import com.example.auth_service.mapper.UserMapper;
import com.example.auth_service.repository.InvalidatedTokenRepository;
import com.example.auth_service.repository.RoleRepository;
import com.example.auth_service.repository.UserAccountEventRepository;
import com.example.auth_service.repository.UserRepository;
import com.example.auth_service.repository.httpclient.FileServiceClient;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GoogleSignUpRaceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final RoleRepository roles = mock(RoleRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final AuthService auth = new AuthService(users, mock(InvalidatedTokenRepository.class),
            mock(UserMapper.class), encoder, mock(FileServiceClient.class),
            roles, mock(UserAccountEventRepository.class), mock(SessionEpochService.class));

    GoogleSignUpRaceTest() {
        var role = new Role();
        role.setName("Khách Hàng");
        role.setActive(true);
        when(roles.findByName("Khách Hàng")).thenReturn(role);
        when(encoder.encode(anyString())).thenReturn("hash");
        ReflectionTestUtils.setField(auth, "customerRoleName", "Khách Hàng");
    }

    @Test
    void racingPasswordAccountIsNotSilentlyLinked() {
        var passwordUser = User.builder().username("victim").email("victim@test.invalid")
                .password("hash").provider("LOCAL").status(UserStatus.ACTIVE).build();
        when(users.findByGoogleId("new-google")).thenReturn(Optional.empty());
        when(users.findByEmail("victim@test.invalid"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(passwordUser));
        when(users.save(any(User.class))).thenThrow(new DataIntegrityViolationException("email"));

        var error = assertThrows(AppException.class,
                () -> auth.findOrCreateGoogleUser("new-google", "victim@test.invalid", "Victim", null));

        assertEquals(ErrorCode.GOOGLE_LINK_REQUIRED, error.getErrorCode());
        assertNull(passwordUser.getGoogleId());
    }

    @Test
    void racingSameGoogleAccountIsReused() {
        var existing = User.builder().username("victim").email("victim@test.invalid")
                .googleId("new-google").provider("GOOGLE").status(UserStatus.ACTIVE).build();
        when(users.findByGoogleId("new-google"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(users.findByEmail("victim@test.invalid")).thenReturn(Optional.empty());
        when(users.save(any(User.class))).thenThrow(new DataIntegrityViolationException("google"));

        assertSame(existing,
                auth.findOrCreateGoogleUser("new-google", "victim@test.invalid", "Victim", null));
    }

    @Test
    void racingInactiveGoogleAccountIsRejected() {
        var existing = User.builder().username("victim").email("victim@test.invalid")
                .googleId("new-google").provider("GOOGLE").status(UserStatus.LOCKED).build();
        when(users.findByGoogleId("new-google"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(users.findByEmail("victim@test.invalid")).thenReturn(Optional.empty());
        when(users.save(any(User.class))).thenThrow(new DataIntegrityViolationException("google"));

        var error = assertThrows(AppException.class,
                () -> auth.findOrCreateGoogleUser("new-google", "victim@test.invalid", "Victim", null));

        assertEquals(ErrorCode.ACCOUNT_INACTIVE, error.getErrorCode());
    }
}
