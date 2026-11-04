package com.example.pricing_service;

import com.example.pricing_service.controller.TourPriceController;
import com.example.pricing_service.config.PriceBatchBodyFilter;
import com.example.pricing_service.dto.request.TourPriceRequest;
import com.example.pricing_service.entity.TourPrice;
import com.example.pricing_service.exception.GlobalExceptionHandler;
import com.example.pricing_service.repository.TourPriceRepository;
import com.example.pricing_service.service.TourPriceService;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PriceValidationTest {
    TourPriceRepository repository;
    TourPriceService service;
    MockMvc mvc;
    @BeforeEach void setup() {
        repository=mock(TourPriceRepository.class);service=new TourPriceService(repository);
        mvc=MockMvcBuilders.standaloneSetup(new TourPriceController(service)).setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new PriceBatchBodyFilter()).build();
    }
    TourPriceRequest valid() {return TourPriceRequest.builder().adultPrice(new BigDecimal("1000000.00"))
            .childPrice(BigDecimal.ZERO).toddlerPrice(BigDecimal.ZERO).infantPrice(BigDecimal.ZERO).singleSupplementPrice(BigDecimal.ZERO).build();}
    @Test void missingNegativeFractionalAndOversizedAmountsAreRejectedBeforeAnyDatabaseCall() {
        for(BigDecimal amount:Arrays.asList(null,new BigDecimal("-1"),BigDecimal.ZERO,new BigDecimal("1000000.01"),new BigDecimal("10000000000"))) {
            var request=valid();request.setAdultPrice(amount);
            assertThrows(ResponseStatusException.class,()->service.createPrice(request));
            assertThrows(ResponseStatusException.class,()->service.updatePrice(1L,request));
        }
        for(int field=0;field<4;field++)for(BigDecimal amount:Arrays.asList(null,new BigDecimal("-1"),new BigDecimal("0.01"),new BigDecimal("10000000000"))) {
            var request=valid();switch(field){case 0->request.setChildPrice(amount);case 1->request.setToddlerPrice(amount);case 2->request.setInfantPrice(amount);default->request.setSingleSupplementPrice(amount);}
            assertThrows(ResponseStatusException.class,()->service.createPrice(request));
        }
        verifyNoInteractions(repository);
    }
    @Test void wholeVndWithDecimalZerosAndFreeChildrenAreAccepted() {
        when(repository.save(any())).thenAnswer(call->{TourPrice p=call.getArgument(0);p.setId(1L);return p;});
        var result=service.createPrice(valid());assertEquals(0,result.getAdultPrice().compareTo(new BigDecimal("1000000")));
        assertEquals(BigDecimal.ZERO,result.getChildPrice());verify(repository).save(any());
    }
    @Test void batchRejectsRawOversizeNullAndNonPositiveIds() {
        for(List<Long> ids:Arrays.asList(null,List.<Long>of(),List.of(0L),List.of(-1L),Arrays.asList(1L,null),Collections.nCopies(101,1L)))
            assertThrows(ResponseStatusException.class,()->service.getPricesBatch(ids));
        verifyNoInteractions(repository);
    }
    @Test void batchAcceptsMaximumAndDeduplicates() {
        when(repository.findAllById(any())).thenReturn(List.of());
        service.getPricesBatch(Collections.nCopies(100,1L));verify(repository).findAllById(List.of(1L));
    }
    @Test void invalidPublicBatchReturns400WithoutQuerying() throws Exception {
        mvc.perform(post("/prices/batch").contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[null,0]}"))
                .andExpect(status().isBadRequest());verifyNoInteractions(repository);
    }
    @Test void invalidPriceReturnsReadable400() throws Exception {
        mvc.perform(post("/prices").contentType(MediaType.APPLICATION_JSON).content("{\"adultPrice\":-1}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Giá người lớn")));
        verifyNoInteractions(repository);
    }
    @Test void oversizedBodyIsRejectedBeforeJsonParsing() throws Exception {
        mvc.perform(post("/prices/batch").contentType(MediaType.APPLICATION_JSON).content(" ".repeat(8193)))
                .andExpect(status().isPayloadTooLarge());verifyNoInteractions(repository);
    }
    @Test void unknownAndMisreportedLengthsStillEnforceActualByteLimit() throws Exception {
        for(int length:new int[]{-1,1}) {
            var request=new MockHttpServletRequest("POST","/prices/batch") {
                @Override public long getContentLengthLong(){return length;}
            };
            request.setContent(new byte[8193]);var response=new MockHttpServletResponse();
            new PriceBatchBodyFilter().doFilter(request,response,(req,res)->fail("Oversized body reached MVC"));
            assertEquals(413,response.getStatus());
        }
    }
    @Test void normalPublicBatchReachesService() throws Exception {
        when(repository.findAllById(List.of(1L))).thenReturn(List.of());
        mvc.perform(post("/prices/batch").contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[1]}"))
                .andExpect(status().isOk());verify(repository).findAllById(List.of(1L));
    }
    @Test void decodedAndMatrixPathsCannotBypassBodyLimit() throws Exception {
        for(String path:List.of("/prices/%62atch","/prices/batch;v=1","/prices/batch/")) {
            var request=new MockHttpServletRequest("POST",path);request.setContent(new byte[8193]);
            var response=new MockHttpServletResponse();
            new PriceBatchBodyFilter().doFilter(request,response,(req,res)->fail("Body limit bypassed"));
            assertEquals(413,response.getStatus());
        }
    }
}
