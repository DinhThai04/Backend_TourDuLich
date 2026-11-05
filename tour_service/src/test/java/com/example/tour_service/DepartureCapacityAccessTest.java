package com.example.tour_service;
import com.example.tour_service.config.*;
import com.example.tour_service.controller.TourDepartureController;
import com.example.tour_service.service.TourDepartureService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
@WebMvcTest(TourDepartureController.class) @Import(SecurityConfig.class)
class DepartureCapacityAccessTest {
 @Autowired MockMvc mvc;
 @MockBean CustomJwtDecoder decoder;
 @MockBean TourDepartureService service;
 @Test void managementSummaryIsNotPublicAndNeedsManagementPermission()throws Exception {
  mvc.perform(get("/tour-departures/1/capacity")).andExpect(status().isUnauthorized());
  mvc.perform(get("/tour-departures/1/capacity").with(jwt())).andExpect(status().isForbidden());
  mvc.perform(get("/tour-departures/1/capacity").with(jwt().authorities(new SimpleGrantedAuthority("PERM:TOUR_DEPARTURES_UPDATE")))).andExpect(status().isOk());
  verify(service).capacity(1);
 }
 @Test void conflictRemainsHttp409WithUsefulMessage()throws Exception {
  when(service.update(eq(1),any())).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"Lịch đã thay đổi; tải lại"));
  mvc.perform(put("/tour-departures/1").contentType("application/json").content("{\"capacity\":20,\"expectedVersion\":0}")
   .with(jwt().authorities(new SimpleGrantedAuthority("PERM:TOUR_DEPARTURES_UPDATE"))))
   .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Lịch đã thay đổi; tải lại"));
 }
 @Test void fractionalCapacityIsRejectedBeforeServiceInvocation()throws Exception {
  mvc.perform(put("/tour-departures/1").contentType("application/json").content("{\"capacity\":12.5,\"expectedVersion\":0}")
   .with(jwt().authorities(new SimpleGrantedAuthority("PERM:TOUR_DEPARTURES_UPDATE"))))
   .andExpect(status().isBadRequest());verifyNoInteractions(service);
 }
 @Test void incidentPlannerCanReadCapacityUsingItsCanonicalPermission()throws Exception {
  mvc.perform(get("/tour-departures/1/capacity").with(jwt().authorities(new SimpleGrantedAuthority("PERM:SHOP_CHANGE_CREATE"))))
      .andExpect(status().isOk());verify(service).capacity(1);
 }
}
