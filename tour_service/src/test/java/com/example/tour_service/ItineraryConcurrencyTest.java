package com.example.tour_service;

import com.example.tour_service.dto.request.ItineraryRequest;
import com.example.tour_service.entity.*;
import com.example.tour_service.enums.LocationType;
import com.example.tour_service.repository.ItineraryRepository;
import com.example.tour_service.service.ItineraryService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties={
    "spring.datasource.url=jdbc:h2:mem:itinerary_concurrency;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
    "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
    "spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ",
    "spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
    "spring.sql.init.mode=never","spring.jpa.show-sql=false"
})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Import(ItineraryService.class)
class ItineraryConcurrencyTest {
    @Autowired ItineraryService service;
    @Autowired ItineraryRepository itineraries;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager manager;
    int tourId;
    int thirdDayId;

    @BeforeEach void seed() {
        new TransactionTemplate(manager).executeWithoutResult(tx->{
            em.createQuery("delete from Itinerary").executeUpdate();
            em.createQuery("delete from Tour").executeUpdate();
            em.createQuery("delete from Location").executeUpdate();
            em.createQuery("delete from Vehicle").executeUpdate();
            var location=Location.builder().city("Fixture").img("").type(LocationType.DEPARTURE).build();em.persist(location);
            var vehicle=Vehicle.builder().id("ITINERARY_BUS").vehicleType("Bus").build();em.persist(vehicle);
            var tour=Tour.builder().tourProgram("ITINERARY").tourTitle("Fixture tour").description("Fixture")
                    .duration(5).basePrice(BigDecimal.ONE).tourPriceId(1L).departureLocation(location).destinationLocation(location).vehicle(vehicle).build();
            em.persist(tour);em.flush();tourId=tour.getId();
            // Insert out of day order so read ordering cannot pass by insertion ID alone.
            for(int day:new int[]{3,1,2}) {
                var itinerary=Itinerary.builder().tour(tour).dayNumber(day).title("Day "+day).description("Fixture").meal("Lunch").build();
                em.persist(itinerary);em.flush();if(day==3)thirdDayId=itinerary.getId();
            }
        });
    }

    private ItineraryRequest request(String title) {
        return ItineraryRequest.builder().tourId(tourId).title(title).description("Fixture").meal("Lunch").build();
    }

    @Test void concurrentCreatesAllocateDifferentDaysEvenWhenDatabaseDefaultsToRepeatableRead() throws Exception {
        var start=new CountDownLatch(1);var ready=new CountDownLatch(2);var pool=Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results=new ArrayList<>();
            for(int i=0;i<2;i++) {int number=i;results.add(pool.submit(()->{
                ready.countDown();assertTrue(start.await(10,TimeUnit.SECONDS));return service.create(request("New "+number)).getDayNumber();
            }));}
            assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();
            Set<Integer> assigned=new HashSet<>();for(var result:results)assigned.add(result.get(25,TimeUnit.SECONDS));
            assertEquals(Set.of(4,5),assigned);
            assertEquals(List.of(1,2,3,4,5),service.getByTourId(tourId).stream().map(day->day.getDayNumber()).toList());
            assertEquals(5,jdbc.queryForObject("SELECT COUNT(DISTINCT day_number) FROM itinerary WHERE tour_id=?",Integer.class,tourId));
        } finally {start.countDown();pool.shutdownNow();}
    }

    @Test void databaseRejectsDuplicateDayEvenForAWriterThatBypassesServiceLock() {
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update(
                "INSERT INTO itinerary(tour_id,day_number,title,description,meal) VALUES(?,3,'Duplicate','Fixture','Lunch')",tourId));
        assertEquals(3,itineraries.findByTourId(tourId).size());
    }

    @Test void emptyTourStillSerializesFirstDayAllocation() throws Exception {
        jdbc.update("DELETE FROM itinerary WHERE tour_id=?",tourId);
        var start=new CountDownLatch(1);var ready=new CountDownLatch(2);var pool=Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results=new ArrayList<>();
            for(int i=0;i<2;i++) {int number=i;results.add(pool.submit(()->{
                ready.countDown();assertTrue(start.await(10,TimeUnit.SECONDS));return service.create(request("First "+number)).getDayNumber();
            }));}
            assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();
            Set<Integer> assigned=new HashSet<>();for(var result:results)assigned.add(result.get(25,TimeUnit.SECONDS));
            assertEquals(Set.of(1,2),assigned);
            assertEquals(List.of(1,2),service.getByTourId(tourId).stream().map(day->day.getDayNumber()).toList());
        } finally {start.countDown();pool.shutdownNow();}
    }

    @Test void readsUseDayOrderRatherThanInsertionOrder() {
        assertEquals(List.of(1,2,3),service.getByTourId(tourId).stream().map(day->day.getDayNumber()).toList());
        assertEquals(List.of(1,2,3),service.getAll().stream().map(day->day.getDayNumber()).toList());
    }

    @Test void updatingAndDeletingPreserveExistingNumberingPolicy() {
        assertEquals(3,service.update(thirdDayId,request("Updated title")).getDayNumber());
        assertEquals("Updated title",service.getById(thirdDayId).getTitle());
        var second=itineraries.findByTourId(tourId).stream().filter(day->day.getDayNumber()==2).findFirst().orElseThrow();
        service.delete(second.getId());
        assertEquals(List.of(1,3),service.getByTourId(tourId).stream().map(day->day.getDayNumber()).toList());
        assertEquals(2,service.create(request("Append after deletion")).getDayNumber());
    }

    @Test void concurrentDeleteAndCreateKeepExistingDaysUnique() throws Exception {
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var remove=pool.submit(()->{start.await();service.delete(thirdDayId);return true;});
            var create=pool.submit(()->{start.await();return service.create(request("New day")).getDayNumber();});
            start.countDown();assertTrue(remove.get(25,TimeUnit.SECONDS));assertTrue(Set.of(3,4).contains(create.get(25,TimeUnit.SECONDS)));
            var days=service.getByTourId(tourId);assertEquals(3,days.size());
            assertEquals(3,days.stream().map(day->day.getDayNumber()).distinct().count());
            assertTrue(days.stream().anyMatch(day->"New day".equals(day.getTitle())));
        } finally {start.countDown();pool.shutdownNow();}
    }
}
