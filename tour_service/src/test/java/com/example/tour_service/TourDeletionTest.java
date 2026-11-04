package com.example.tour_service;

import com.example.tour_service.entity.Itinerary;
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
import com.example.tour_service.service.TourDepartureService;
import com.example.tour_service.service.TourService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:tour_deletion;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.sql.init.mode=never",
        "spring.jpa.show-sql=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TourDeletionTest {
    @TempDir Path tempDir;
    @Autowired TourRepository tours;
    @Autowired ItineraryRepository itineraries;
    @Autowired EntityManager em;
    private final FileClient files = mock(FileClient.class);
    private final org.springframework.jdbc.core.JdbcTemplate jdbc =
            mock(org.springframework.jdbc.core.JdbcTemplate.class);
    private TourService service;
    private int tourId;

    @BeforeEach
    void setUp() {
        when(files.getTourMedia(any())).thenReturn(List.of());
        service = new TourService(tours, itineraries, mock(LocationRepository.class), mock(VehicleRepository.class),
                mock(PricingClient.class), files,
                new com.example.tour_service.service.TourImageCache(mock(FileClient.class), tours,
                        new com.fasterxml.jackson.databind.ObjectMapper(),
                        tempDir.resolve("tour-images.json").toString()),
                mock(TourDepartureService.class),
                new org.springframework.transaction.support.TransactionTemplate(
                        mock(org.springframework.transaction.PlatformTransactionManager.class)),
                jdbc);
        Location location = Location.builder().city("Fixture").img("").type(LocationType.DEPARTURE).build();
        em.persist(location);
        Vehicle vehicle = Vehicle.builder().id("DELETE_BUS").vehicleType("Bus").build();
        em.persist(vehicle);
        Tour tour = Tour.builder().tourProgram("DELETE").tourTitle("Deletion fixture").description("Fixture")
                .duration(2).basePrice(BigDecimal.ONE).tourPriceId(1L).departureLocation(location)
                .destinationLocation(location).vehicle(vehicle).build();
        em.persist(tour);
        em.flush();
        tourId = tour.getId();
        for (int day = 1; day <= 2; day++) {
            em.persist(Itinerary.builder().tour(tour).dayNumber(day).title("Day " + day)
                    .description("Fixture").meal("Lunch").build());
        }
        em.flush();
    }

    @Test
    void deletesOwnedItinerariesWithTour() {
        service.deleteTour(tourId);

        assertFalse(tours.existsById(tourId));
        assertEquals(0, itineraries.count());
        // The legacy join table has no entity mapping, so the delete must be issued explicitly.
        verify(jdbc).update("DELETE FROM tour_image_ids WHERE tour_id=?", tourId);
    }

    @Test
    void refusesToDeleteWhenGalleryCannotBeRead() {
        when(files.getTourMedia(any())).thenThrow(new RuntimeException("file service down"));

        var error = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.deleteTour(tourId));

        assertEquals(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
        assertTrue(tours.existsById(tourId));
        assertEquals(2, itineraries.count());
        verify(jdbc, never()).update("DELETE FROM tour_image_ids WHERE tour_id=?", tourId);
    }

    @Test
    void missingTourFailsBeforeTheFileServiceIsCalled() {
        var error = assertThrows(com.example.tour_service.exception.AppException.class,
                () -> service.deleteTour(999));

        assertEquals(com.example.tour_service.exception.ErrorCode.TOUR_NOT_FOUND, error.getErrorCode());
        verify(files, never()).getTourMedia(any());
    }
}
