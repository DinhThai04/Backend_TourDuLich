package com.example.tour_service.advice;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import com.example.tour_service.dto.request.ApiResponse;
import com.example.tour_service.dto.response.TourPriceResponse;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class AdviceLocationIsolationTest {
    @Test void malformedLegacyTourDoesNotBreakAnotherDestinationSearch() {
        var repository=mock(AdviceCatalogRepository.class);
        var prices=mock(AdvicePricingClient.class);
        when(repository.places()).thenReturn(List.of(
                new AdviceCatalogRepository.Place(1,"Hà Nội","DEPARTURE"),
                new AdviceCatalogRepository.Place(2,"Hà Giang","DESTINATION"),
                new AdviceCatalogRepository.Place(3,"Đà Nẵng","DESTINATION")));
        when(repository.candidates()).thenReturn(List.of(
                new AdviceCatalogRepository.Candidate(1,"BAD","Legacy invalid",3,1L,2,2),
                new AdviceCatalogRepository.Candidate(2,"GOOD","Đà Nẵng",3,1L,1,3)));
        when(prices.prices(any())).thenReturn(ApiResponse.<List<TourPriceResponse>>builder().result(List.of(
                TourPriceResponse.builder().id(1L).adultPrice(BigDecimal.TEN).childPrice(BigDecimal.ZERO)
                    .toddlerPrice(BigDecimal.ZERO).infantPrice(BigDecimal.ZERO).build())).build());
        var service=new AdviceService(repository,prices,mock(AdviceMediaClient.class),new AdviceLocations(),
                new AdviceConfiguration.Limits(null,null,null),Clock.systemUTC());
        var criteria=new AdviceModels.Criteria(null,3,null,null,null,null,null,null,AdviceModels.SearchMode.CATALOG);
        var result=service.search(new AdviceModels.SearchRequest(criteria,0,5));
        assertEquals(List.of(2),result.tours().stream().map(AdviceModels.TourCard::tourId).toList());
    }
}
