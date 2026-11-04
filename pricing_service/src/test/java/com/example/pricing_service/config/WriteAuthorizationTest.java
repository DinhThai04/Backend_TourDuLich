package com.example.pricing_service.config;
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
        @GetMapping("/prices") String read() { return "ok"; }
        @GetMapping("/prices/{id}") String readOne() { return "ok"; }
        @PostMapping("/prices") String write() { return "ok"; }
        @PostMapping("/prices/batch") String batch() { return "ok"; }
    }
    @Test void anonymousCannotWrite() throws Exception {
        mvc.perform(post("/prices")).andExpect(status().isUnauthorized());
    }
    @Test void customerCannotWrite() throws Exception {
        mvc.perform(post("/prices").with(jwt().jwt(j -> j.subject("2"))))
            .andExpect(status().isForbidden());
    }
    @Test void adminAndDelegatedPermissionCanWrite() throws Exception {
        for (String permission : new String[]{"SGU_SUPER_ADMIN", "PERM:PRICE_CREATE"})
            mvc.perform(post("/prices").with(jwt().authorities(new SimpleGrantedAuthority(permission))))
                .andExpect(status().isOk());
    }
    @Test void publicReadRemainsAvailable() throws Exception {
        mvc.perform(get("/prices/1")).andExpect(status().isOk());
        mvc.perform(get("/prices")).andExpect(status().isUnauthorized());
    }
    @Test void publicBatchIsReadOnlyAndAccessible() throws Exception { mvc.perform(post("/prices/batch")).andExpect(status().isOk()); }
}
