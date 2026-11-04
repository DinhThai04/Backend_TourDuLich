package com.example.tour_service.service;

import com.example.tour_service.dto.request.LocationRequest;
import com.example.tour_service.dto.request.VehicleRequest;
import com.example.tour_service.entity.Location;
import com.example.tour_service.entity.Tour;
import com.example.tour_service.entity.Vehicle;
import com.example.tour_service.enums.LocationType;
import com.example.tour_service.repository.LocationRepository;
import com.example.tour_service.repository.httpClient.FileClient;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Reference data guards: an id belongs to one row, and a referenced place keeps the type its tours list. */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:reference_guards;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.sql.init.mode=never"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({VehicleService.class, LocationService.class})
class ReferenceDataGuardTest {
    @Autowired VehicleService vehicles;
    @Autowired LocationService locations;
    @Autowired LocationRepository locationRepository;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @MockBean FileClient files;

    @Test void reusedVehicleIdCannotOverwriteTheExistingRow() {
        vehicles.createVehicle(VehicleRequest.builder().id("BUS-1").vehicleType("Bus 16 chỗ").build());

        var duplicate = assertThrows(ResponseStatusException.class, () ->
                vehicles.createVehicle(VehicleRequest.builder().id("BUS-1").vehicleType("Xe limousine").build()));

        assertEquals(HttpStatus.CONFLICT, duplicate.getStatusCode());
        // Read through plain JDBC: the rejected insert left the JPA session marked for rollback.
        assertEquals("Bus 16 chỗ",
                jdbc.queryForObject("SELECT vehicle_type FROM vehicles WHERE id=?", String.class, "BUS-1"));
    }

    @Test void locationKeepsItsTypeWhileToursReferenceIt() {
        var origin = Location.builder().city("Nha Trang").img("").type(LocationType.DEPARTURE).build();
        em.persist(origin);
        var vehicle = Vehicle.builder().id("BUS-2").vehicleType("Bus").build();
        em.persist(vehicle);
        em.persist(Tour.builder().tourProgram("P").tourTitle("Tour").description("Fixture").duration(2)
                .basePrice(BigDecimal.ONE).tourPriceId(1L).departureLocation(origin)
                .destinationLocation(origin).vehicle(vehicle).build());
        em.flush();

        var retyped = assertThrows(ResponseStatusException.class, () -> locations.updateLocation(origin.getId(),
                LocationRequest.builder().city("Nha Trang").type(LocationType.DESTINATION).build()));
        assertEquals(HttpStatus.CONFLICT, retyped.getStatusCode());

        var renamed = locations.updateLocation(origin.getId(),
                LocationRequest.builder().city("Nha Trang city").type(LocationType.DEPARTURE).build());
        assertEquals("Nha Trang city", renamed.getCity());
        assertEquals(LocationType.DEPARTURE,
                locationRepository.findById(origin.getId()).orElseThrow().getType());
    }
}
