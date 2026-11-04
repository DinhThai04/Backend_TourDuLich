package com.example.tour_service.config;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
@WebMvcTest(controllers = WriteAuthorizationTest.TestApi.class)
@Import({SecurityConfig.class, WriteAuthorizationTest.TestApi.class})
class WriteAuthorizationTest {
    @Autowired MockMvc mvc;
    @MockBean CustomJwtDecoder decoder;
    @RestController static class TestApi {
        @GetMapping("/tours/list") String read() { return "ok"; }
        @GetMapping("/tour-departures/list") String adminList() { return "ok"; }
        @PostMapping("/tours") String write() { return "ok"; }
        @PostMapping("/prices/batch") String batch() { return "ok"; }
    }
    @Test void anonymousCannotWrite() throws Exception {
        mvc.perform(post("/tours")).andExpect(status().isUnauthorized());
    }
    @Test void customerCannotWrite() throws Exception {
        mvc.perform(post("/tours").with(jwt().jwt(j -> j.subject("2"))))
            .andExpect(status().isForbidden());
    }
    @Test void adminAndDelegatedPermissionCanWrite() throws Exception {
        for (String permission : new String[]{"SGU_SUPER_ADMIN", "PERM:TOURS_CREATE"})
            mvc.perform(post("/tours").with(jwt().authorities(new SimpleGrantedAuthority(permission))))
                .andExpect(status().isOk());
    }
    @Test void publicReadRemainsAvailable() throws Exception {
        mvc.perform(get("/tours/list")).andExpect(status().isOk());
    }
    @Test void adminListingIsNotPublic() throws Exception {
        mvc.perform(get("/tour-departures/list")).andExpect(status().isUnauthorized());
    }
    @Test void viewOnlyApiPermissionCannotCreateTour() throws Exception {
        mvc.perform(post("/tours").with(jwt().authorities(new SimpleGrantedAuthority("PERM:TOURS_GET_LIST"))))
                .andExpect(status().isForbidden());
    }
    
}
