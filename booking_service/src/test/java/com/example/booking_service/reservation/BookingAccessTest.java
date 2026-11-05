package com.example.booking_service.reservation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BookingAccessTest {
    BookingAccess access = new BookingAccess();
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    void login(String... permissions) {
        var jwt = Jwt.withTokenValue("unit").header("alg","HS512").subject("7").build();
        var authorities = java.util.Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,authorities));
    }
    @Test void ownershipComesFromJwtAndCannotBeChosenByCaller() {
        login("ROLE_Khách Hàng"); assertEquals(7,access.accountId());
        access.requireOwner(7); assertThrows(ResponseStatusException.class,()->access.requireOwner(8));
    }
    @Test void adminAccessUsesPermissionRatherThanHardcodedRoleName() {
        login("ROLE_admin"); assertFalse(access.admin());
        login("SGU_SUPER_ADMIN"); assertTrue(access.admin()); access.requireOwner(8);
    }
    @Test void delegatedReadCannotCancelOthersAndLegacyAdminScopeIsNotPrivileged() {
        login("ACCESS_ADMIN");assertFalse(access.admin());assertFalse(access.can("BOOKINGS_UPDATE_STATUS"));
        login("PERM:BOOKINGS_GET_ALL");access.requireReadOwner(8,"BOOKINGS_GET_ALL");
        assertThrows(ResponseStatusException.class,()->access.requireOwner(8));
    }
}
