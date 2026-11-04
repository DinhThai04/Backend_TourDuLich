package com.example.search_service;

import com.example.search_service.controller.TourSearchController;
import com.example.search_service.exception.GlobalExceptionHandler;
import com.example.search_service.service.CatalogSynchronizer;
import com.example.search_service.service.TourSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Malformed or out-of-window search requests must answer 400, never a 500 from the query layer. */
class SearchRequestErrorTest {
    private final TourSearchService service = mock(TourSearchService.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new TourSearchController(service, mock(CatalogSynchronizer.class)))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void outOfWindowPageIsRejectedByTheServiceGuard() throws Exception {
        when(service.findAll(anyInt(), anyInt())).thenThrow(
                new IllegalArgumentException("Trang quá sâu; chỉ xem được 10000 kết quả đầu tiên"));
        mvc.perform(get("/tours").param("page", "10000").param("size", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1400));
    }

    @Test
    void malformedPagingParametersReturnBadRequest() throws Exception {
        mvc.perform(get("/tours").param("page", "abc").param("size", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1400));
    }

    @Test
    void missingPagingParametersUseDefaults() throws Exception {
        mvc.perform(get("/tours"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000));
    }
}
