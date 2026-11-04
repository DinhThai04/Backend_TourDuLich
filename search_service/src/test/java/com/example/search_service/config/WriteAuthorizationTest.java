package com.example.search_service.config;
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
        @GetMapping("/tours") String read() { return "ok"; }
        @PostMapping("/tours/sync") String sync() { return "ok"; }
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
    @Test void indexWritesAreNotAvailableThroughRequestBodies() throws Exception {
        for (String permission : new String[]{"SGU_SUPER_ADMIN", "PERM:SEARCH_SYNC", "ACCESS_ADMIN"})
            mvc.perform(post("/tours").with(jwt().authorities(new SimpleGrantedAuthority(permission))))
                .andExpect(status().isForbidden());
    }
    @Test void publicReadRemainsAvailable() throws Exception {
        mvc.perform(get("/tours")).andExpect(status().isOk());
    }
    
    @Test void uiEntryAndLegacyAdminClaimCannotWriteOrSync() throws Exception {
        for(String scope:new String[]{"PERM:TOURS_GET_LIST","ACCESS_ADMIN"}) {
            mvc.perform(post("/tours").with(jwt().authorities(new SimpleGrantedAuthority(scope)))).andExpect(status().isForbidden());
            mvc.perform(post("/tours/sync").with(jwt().authorities(new SimpleGrantedAuthority(scope)))).andExpect(status().isForbidden());
        }
        mvc.perform(post("/tours/sync").with(jwt().authorities(new SimpleGrantedAuthority("SGU_SUPER_ADMIN")))).andExpect(status().isOk());
    }
}
