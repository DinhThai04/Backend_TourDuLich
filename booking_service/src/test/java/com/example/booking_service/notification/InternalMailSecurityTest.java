package com.example.booking_service.notification;

import com.example.booking_service.config.SecurityConfig;
import com.example.booking_service.config.CustomJwtDecoder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=InternalMailController.class,properties="app.mail.internal-api-key=test-only-internal-key-0123456789abcdef")
@Import(SecurityConfig.class)
class InternalMailSecurityTest {
    static final String PATH="/internal/mail/reminders/dispatch";
    static final String KEY="test-only-internal-key-0123456789abcdef";
    @Autowired MockMvc mvc;
    @MockBean ReminderPlanner planner;
    @MockBean CustomJwtDecoder decoder;
    void result() {
        when(planner.dispatch(any())).thenReturn(new ReminderPlanner.Result(true,0,0,0,0,0,0,0,false,0));
    }
    @Test void missingOrWrongKeyAndBearerAloneCannotDispatch() throws Exception {
        mvc.perform(post(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).header("X-Internal-Mail-Key","wrong")).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).header("Authorization","Bearer admin-token")).andExpect(status().isUnauthorized());
        verifyNoInteractions(planner);
    }
    @Test void correctKeyCanDispatchWithSafeDefaultPreview() throws Exception {
        result();mvc.perform(post(PATH).header("X-Internal-Mail-Key",KEY)).andExpect(status().isOk());
        verify(planner).dispatch(new ReminderPlanner.Request(true,100,0));
    }
    @Test void emptyJsonStillDefaultsToPreview() throws Exception {
        result();mvc.perform(post(PATH).header("X-Internal-Mail-Key",KEY).contentType("application/json").content("{}"))
                .andExpect(status().isOk());verify(planner).dispatch(new ReminderPlanner.Request(true,100,0));
    }
    @Test void directServiceKeyWorksWithBookingContextPath() throws Exception {
        result();mvc.perform(post("/booking"+PATH).contextPath("/booking").header("X-Internal-Mail-Key",KEY))
                .andExpect(status().isOk());
    }
    @Test void wrongMethodOrOtherPathCannotUseInternalCredential() throws Exception {
        mvc.perform(get(PATH).header("X-Internal-Mail-Key",KEY)).andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/book/create").header("X-Internal-Mail-Key",KEY).contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized()); // the mail key is not a JWT, so the entry point answers 401
    }
    @Test void badLimitIsRejectedBeforePlanner() throws Exception {
        mvc.perform(post(PATH).header("X-Internal-Mail-Key",KEY).contentType("application/json")
                .content("{\"dryRun\":false,\"batchSize\":201,\"afterBookingId\":0}"))
                .andExpect(status().isBadRequest());verifyNoInteractions(planner);
    }
    @Test void incompletePlanningStillAdvancesPaginationForWorkflow() throws Exception {
        // A single poisoned booking must not abort the whole scan: report it in the payload, keep 200.
        when(planner.dispatch(any())).thenReturn(new ReminderPlanner.Result(false,1,0,0,0,0,1,7,true,0));
        mvc.perform(post(PATH).header("X-Internal-Mail-Key",KEY).contentType("application/json").content("{\"dryRun\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1500))
                .andExpect(jsonPath("$.result.failed").value(1))
                .andExpect(jsonPath("$.result.nextCursor").value(7))
                .andExpect(jsonPath("$.result.hasMore").value(true));
    }
    @Test void invalidRecipientWarningStillAllowsWorkflowToAdvance() throws Exception {
        when(planner.dispatch(any())).thenReturn(new ReminderPlanner.Result(false,1,0,0,0,1,0,1,true,1));
        mvc.perform(post(PATH).header("X-Internal-Mail-Key",KEY).contentType("application/json").content("{\"dryRun\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.invalidRecipients").value(1)).andExpect(jsonPath("$.result.hasMore").value(true));
    }
}
