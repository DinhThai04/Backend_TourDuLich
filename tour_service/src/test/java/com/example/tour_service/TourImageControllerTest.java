package com.example.tour_service;

import com.example.tour_service.config.*;
import com.example.tour_service.controller.TourController;
import com.example.tour_service.dto.response.TourResponse;
import com.example.tour_service.service.TourService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TourController.class)
@Import(SecurityConfig.class)
class TourImageControllerTest {
    @Autowired MockMvc mvc;
    @MockBean CustomJwtDecoder decoder;
    @MockBean TourService tours;
    MockMultipartFile file() { return new MockMultipartFile("files","tour.png","image/png",new byte[]{1}); }
    @Test void createBindsMultipartFilesAndReturnsPendingTourId() throws Exception {
        when(tours.createTour(any())).thenReturn(TourResponse.builder().id(42).imageUploadStatus("PENDING").imageIds(List.of()).build());
        mvc.perform(multipart("/tours").file(file())
                .param("tourProgram","Chương trình").param("tourTitle","Test").param("description","Mô tả")
                .param("duration","2").param("departureLocationId","1").param("destinationLocationId","2")
                .param("vehicleId","BUS").param("tourPriceId","5")
                .with(jwt().authorities(new SimpleGrantedAuthority("PERM:TOURS_CREATE"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.id").value(42)).andExpect(jsonPath("$.result.imageUploadStatus").value("PENDING"));
        verify(tours).createTour(argThat(request->request.getDuration()==2 && request.getFiles().size()==1 && "tour.png".equals(request.getFiles().getFirst().getOriginalFilename())));
    }
    @Test void createRejectsMissingRequiredFields() throws Exception {
        mvc.perform(multipart("/tours").file(file()).param("tourTitle","Test")
                .with(jwt().authorities(new SimpleGrantedAuthority("PERM:TOURS_CREATE"))))
                .andExpect(status().isBadRequest());
        verify(tours,never()).createTour(any());
    }
    @Test void creatorsAndEditorsCanRetryImagesWithoutCreatingTours() throws Exception {
        when(tours.retryTourImages(eq(42),any())).thenReturn(TourResponse.builder().id(42).imageUploadStatus("COMPLETE").imageIds(List.of("image.png")).build());
        for(String scope:List.of("PERM:TOURS_CREATE","PERM:TOURS_UPDATE")) {
            mvc.perform(multipart("/tours/42/images").file(file()).with(jwt().authorities(new SimpleGrantedAuthority(scope))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.result.imageIds[0]").value("image.png"));
        }
        verify(tours,never()).createTour(any());verify(tours,times(2)).retryTourImages(eq(42),any());
    }
    @Test void customersCannotRetryImagesOnAnyTour() throws Exception {
        mvc.perform(multipart("/tours/42/images").file(file()).with(jwt().jwt(token->token.subject("2"))))
                .andExpect(status().isForbidden());verifyNoInteractions(tours);
    }
}
