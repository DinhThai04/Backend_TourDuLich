package com.example.tour_service;

import com.example.tour_service.dto.request.TourDepartureRequest;
import com.example.tour_service.entity.Location;
import com.example.tour_service.entity.Tour;
import com.example.tour_service.entity.TourDeparture;
import com.example.tour_service.entity.Vehicle;
import com.example.tour_service.enums.LocationType;
import com.example.tour_service.repository.httpclient.PricingClient;
import com.example.tour_service.service.DepartureCapacityReader;
import com.example.tour_service.service.TourDepartureService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=create-drop", "spring.sql.init.mode=never", "spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({TourDepartureService.class, DepartureCapacityReader.class})
class DepartureCapacityTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:h2:mem:capacity;MODE=MySQL;DB_CLOSE_DELAY=-1");
        properties.add("spring.datasource.username", () -> "sa");
        properties.add("spring.datasource.password", () -> "");
        properties.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        properties.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.H2Dialect");
    }

    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired TourDepartureService service;
    @MockBean PricingClient pricing;

    final LocalDateTime start = LocalDateTime.of(2090, 1, 20, 7, 0);
    int id, tourId;
    TransactionTemplate tx;

    @BeforeEach
    void seed() {
        tx = new TransactionTemplate(manager);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS departure_hold(
                    hold_id VARCHAR(36) PRIMARY KEY,
                    departure_id INT NOT NULL,
                    booking_id INT NOT NULL,
                    seats INT NOT NULL,
                    status VARCHAR(20) NOT NULL,
                    expires_at DATETIME(6) NOT NULL,
                    version BIGINT NOT NULL DEFAULT 0,
                    created_at DATETIME(6) NOT NULL
                )
                """);
        for (String table : List.of("departure_hold", "tour_departure", "tour", "location", "vehicles")) {
            jdbc.update("DELETE FROM " + table);
        }
        tx.executeWithoutResult(status -> {
            var location = Location.builder().city("Fixture").img("fixture.png").type(LocationType.DEPARTURE).build();
            em.persist(location);
            var vehicle = Vehicle.builder().id("CAP-BUS").vehicleType("Bus").build();
            em.persist(vehicle);
            var tour = Tour.builder().tourProgram("CAP").tourTitle("Capacity fixture").description("Test only")
                    .duration(2).basePrice(BigDecimal.valueOf(3350000)).tourPriceId(1L)
                    .departureLocation(location).destinationLocation(location).vehicle(vehicle).build();
            em.persist(tour);
            tourId = tour.getId();
            var departure = TourDeparture.builder().tour(tour).tourCode("CAP-TEST")
                    .departureDate(start).returnDate(start.plusDays(2)).availableSeats((short) 10).capacity(10).build();
            em.persist(departure);
            em.flush();
            id = departure.getId();
        });
    }

    private String addHold(int bookingId, int seats, String status, LocalDateTime expiresAt) {
        String holdId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO departure_hold(hold_id,departure_id,booking_id,seats,status,expires_at,version,created_at) VALUES(?,?,?,?,?,?,0,?)",
                holdId, id, bookingId, seats, status, expiresAt, LocalDateTime.now());
        return holdId;
    }

    TourDepartureRequest edit(int capacity, long version) {
        return TourDepartureRequest.builder().capacity(capacity).expectedVersion(version).build();
    }

    void conflict(Runnable action) {
        assertEquals(409, assertThrows(ResponseStatusException.class, action::run).getStatusCode().value());
    }

    int seats() {
        return jdbc.queryForObject("SELECT available_seats FROM tour_departure WHERE id=?", Integer.class, id);
    }

    @Test
    void upcomingReturnsOnlyOpenDeparturesWithinSevenDays() {
        var now = LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        assertTrue(service.upcoming(0).items().isEmpty());
        jdbc.update("UPDATE tour_departure SET departure_date=?,return_date=? WHERE id=?",
                now.plusDays(1), now.plusDays(2), id);
        var page = service.upcoming(0);
        assertEquals(1, page.items().size());
        assertEquals(id, page.items().getFirst().id());
        assertEquals("Capacity fixture", page.items().getFirst().tourTitle());
        assertFalse(page.hasNext());
        jdbc.update("UPDATE tour_departure SET operational_status='SUSPENDED' WHERE id=?", id);
        assertTrue(service.upcoming(0).items().isEmpty());
    }

    @Test
    void updatesEmptyCapacityAndScheduleButRejectsStaleFormAndAbsoluteRemaining() {
        var request = edit(20, 0);
        request.setDepartureDate(start.plusDays(1));
        request.setReturnDate(start.plusDays(3));
        conflict(() -> service.update(id, request));
        assertEquals(20, service.update(id, edit(20, 0)).getAvailableSeats().intValue());
        assertEquals(1, service.capacity(id).version());
        conflict(() -> service.update(id, edit(30, 0)));
        var old = edit(20, 1);
        old.setAvailableSeats((short) 30);
        assertThrows(IllegalArgumentException.class, () -> service.update(id, old));
        var invalid = edit(1, 1);
        invalid.setReturnDate(start.minusDays(1));
        conflict(() -> service.update(id, invalid));
    }

    @Test
    void cannotDeleteTourWithHoldHistory() {
        assertDoesNotThrow(() -> service.assertTourHasNoHistory(tourId));
        addHold(101, 2, "CONFIRMED", start.minusDays(1));
        conflict(() -> service.assertTourHasNoHistory(tourId));
    }

    @Test
    void confirmedHoldIsCountedAsPaidAndUnknownCapacityRequiresConfirmation() {
        addHold(102, 2, "CONFIRMED", start.minusDays(1));
        jdbc.update("UPDATE tour_departure SET capacity=NULL,available_seats=8 WHERE id=?", id);
        var state = service.capacity(id);
        assertEquals(2, state.paidSeats());
        assertFalse(state.scheduleEditable());
        assertTrue(state.capacityReviewRequired());
        conflict(() -> service.update(id, edit(12, state.version())));
        var confirmed = edit(12, state.version());
        confirmed.setConfirmCapacity(true);
        assertEquals(10, service.update(id, confirmed).getAvailableSeats().intValue());
        var changedDate = edit(12, service.capacity(id).version());
        changedDate.setDepartureDate(start.plusDays(1));
        conflict(() -> service.update(id, changedDate));
    }

    @Test
    void heldSeatsCannotBeRemovedByReducingCapacityAndReleasedHistoryStillLocksDates() {
        String holdId = addHold(103, 3, "HELD", start.minusDays(1));
        jdbc.update("UPDATE tour_departure SET available_seats=7 WHERE id=?", id);
        assertEquals(3, service.capacity(id).heldSeats());
        conflict(() -> service.update(id, edit(2, 0)));
        assertEquals(0, service.update(id, edit(3, 0)).getAvailableSeats().intValue());
        jdbc.update("UPDATE departure_hold SET status='RELEASED' WHERE hold_id=?", holdId);
        jdbc.update("UPDATE tour_departure SET available_seats=capacity WHERE id=?", id);
        var request = edit(3, service.capacity(id).version());
        request.setReturnDate(start.plusDays(3));
        conflict(() -> service.update(id, request));
    }

    @Test
    void confirmedRepairFixesANonNullCapacityMismatchWithoutHidingOtherIssues() {
        addHold(104, 1, "HELD", start.minusDays(1));
        // available_seats is still 10 while capacity=10 and held=1 -> mismatch
        var state = service.capacity(id);
        assertTrue(state.capacityReviewRequired());
        assertTrue(state.blockingIssues().isEmpty());
        assertTrue(state.issues().stream().anyMatch(issue -> issue.contains("Tổng sức chứa không khớp")));
        conflict(() -> service.update(id, edit(12, 0)));
        var confirmed = edit(12, state.version());
        confirmed.setConfirmCapacity(true);
        assertEquals(11, service.update(id, confirmed).getAvailableSeats().intValue());
    }

    @Test
    void overdueHoldIsReportedWithoutRepair() {
        addHold(105, 1, "HELD", LocalDateTime.of(2000, 1, 1, 0, 0));
        jdbc.update("UPDATE tour_departure SET available_seats=9 WHERE id=?", id);
        assertEquals(1, service.capacity(id).overdueHolds());
        conflict(() -> service.update(id, edit(20, 0)));
        assertEquals(9, seats());
    }

    @Test
    void onlyOneAdministratorCanSaveTheSameVersion() throws Exception {
        var gate = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<Future<Boolean>>();
            for (int total : List.of(11, 12)) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    try {
                        service.update(id, edit(total, 0));
                        return true;
                    } catch (ResponseStatusException stale) {
                        assertEquals(409, stale.getStatusCode().value());
                        return false;
                    }
                }));
            }
            int accepted = 0;
            for (var future : futures) {
                if (future.get(20, TimeUnit.SECONDS)) accepted++;
            }
            assertEquals(1, accepted);
            assertEquals(1, service.capacity(id).version());
        }
    }

    @Test
    void createValidatesCapacityAndFutureDates() {
        var request = TourDepartureRequest.builder().tourId(tourId)
                .departureDate(start.plusDays(4)).returnDate(start.plusDays(5)).capacity(0).build();
        assertThrows(IllegalArgumentException.class, () -> service.create(request));
        request.setCapacity(32768);
        assertThrows(IllegalArgumentException.class, () -> service.create(request));
        request.setCapacity(1);
        request.setDepartureDate(LocalDateTime.of(2000, 1, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> service.create(request));
    }
}
