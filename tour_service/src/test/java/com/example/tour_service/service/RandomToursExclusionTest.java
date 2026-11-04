package com.example.tour_service.service;

import com.example.tour_service.dto.response.TourResponse;
import com.example.tour_service.entity.Location;
import com.example.tour_service.entity.Tour;
import com.example.tour_service.entity.Vehicle;
import com.example.tour_service.enums.LocationType;
import com.example.tour_service.repository.ItineraryRepository;
import com.example.tour_service.repository.LocationRepository;
import com.example.tour_service.repository.TourRepository;
import com.example.tour_service.repository.VehicleRepository;
import com.example.tour_service.repository.httpClient.FileClient;
import com.example.tour_service.repository.httpClient.PricingClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RandomToursExclusionTest {

    private final TourRepository tourRepository = mock(TourRepository.class);
    private final TourImageCache tourImageCache = mock(TourImageCache.class);
    private final TourDepartureService tourDepartureService = mock(TourDepartureService.class);

    private final TourService tourService = new TourService(
            tourRepository,
            mock(ItineraryRepository.class),
            mock(LocationRepository.class),
            mock(VehicleRepository.class),
            mock(PricingClient.class),
            mock(FileClient.class),
            tourImageCache,
            tourDepartureService,
            mock(TransactionTemplate.class),
            mock(org.springframework.jdbc.core.JdbcTemplate.class));

    @Test
    void excludingCurrentTourStillReturnsThreeOtherTours() {
        when(tourRepository.findRandom3(any())).thenReturn(List.of(tour(1), tour(2), tour(3), tour(4)));
        when(tourImageCache.byTourId()).thenReturn(Map.of());
        when(tourDepartureService.getDeparturesByTourIdsWithPrice(anyList())).thenReturn(Map.of());

        List<TourResponse> result = tourService.getRandom3Tours(2);

        assertEquals(List.of(1, 3, 4), result.stream().map(TourResponse::getId).toList());
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(tourRepository).findRandom3(pageable.capture());
        assertEquals(4, pageable.getValue().getPageSize());
    }

    private static Tour tour(int id) {
        Location departure = Location.builder().id(1).city("TP. Hồ Chí Minh").type(LocationType.DEPARTURE).img("a").build();
        Location destination = Location.builder().id(2).city("Đà Lạt").type(LocationType.DESTINATION).img("b").build();
        Vehicle vehicle = Vehicle.builder().id("XE").vehicleType("Xe khách").build();
        return Tour.builder()
                .id(id)
                .tourProgram("TOUR" + id)
                .tourTitle("Tour " + id)
                .departureLocation(departure)
                .destinationLocation(destination)
                .vehicle(vehicle)
                .build();
    }
}
