package com.example.tour_service;

import com.example.tour_service.entity.*;
import com.example.tour_service.enums.LocationType;
import com.example.tour_service.repository.*;
import com.example.tour_service.dto.request.TourSearchRequest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop", "spring.sql.init.mode=never",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"})
class TourSearchPersistenceTest {
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired TourRepository tours;
    int a,b,c,d;
    private static final LocalDateTime NOW=LocalDateTime.of(2026,11,1,0,0);
    @BeforeEach void seed() {
        var origin=Location.builder().city("Origin").type(LocationType.DEPARTURE).img("origin.png").build(); em.persist(origin);
        var destination=Location.builder().city("Destination").type(LocationType.DESTINATION).img("dest.png").build(); em.persist(destination);
        var vehicle=Vehicle.builder().id("BUS").vehicleType("BUS").build();em.persist(vehicle);
        var ids=new ArrayList<Integer>();
        int n=0;
        for(long price:new long[]{3000000,8000000,1000000,5000000}) {
            long priceId=++n; jdbc.update("INSERT INTO prices(id,adult_price) VALUES (?,?)",priceId,price);
            var tour=Tour.builder().tourProgram("P"+n).tourTitle("Tour "+n).description("Fixture").duration(2)
                    .basePrice(BigDecimal.ONE).tourPriceId(priceId).departureLocation(origin).destinationLocation(destination).vehicle(vehicle).build();
            em.persist(tour);ids.add(tour.getId());
            for(String date: switch(n) {
                case 1 -> List.of("2026-11-20T08:00:00", "2026-11-25T08:00:00");
                case 2 -> List.of("2026-11-10T08:00:00", "2026-11-10T10:00:00");
                case 3 -> List.of("2026-10-01T08:00:00");
                default -> List.of("2026-11-10T09:00:00", "2026-11-10T11:00:00");
            }) {
                LocalDateTime start=LocalDateTime.parse(date);
                em.persist(TourDeparture.builder().tour(tour).tourCode("D"+n+date).departureDate(start).returnDate(start.plusDays(2)).availableSeats((short)30).build());
            }
        }
        em.flush();em.clear();a=ids.get(0);b=ids.get(1);c=ids.get(2);d=ids.get(3);
    }
    @Test void displayAssociationsAreFetchedBeforeTheSessionCloses() {
        // open-in-view is off in production, so DTO mapping must not need a second session.
        var page = tours.findAll(PageRequest.of(0, 10));
        var filtered = tours.findAll(TourSpecification.build(new TourSearchRequest(), NOW), PageRequest.of(0, 10));
        var random = tours.findRandom3(PageRequest.of(0, 3));
        em.clear();
        assertEquals("Origin", page.getContent().getFirst().getDepartureLocation().getCity());
        assertEquals("Destination", filtered.getContent().getFirst().getDestinationLocation().getCity());
        assertEquals(3, random.size());
        assertEquals("BUS", random.getFirst().getVehicle().getVehicleType());
    }
    @Test void closestDepartureUsesDatabasePagingWithoutDuplicateToursAndPutsPastOnlyLast() {
        var spec=TourSpecification.build(new TourSearchRequest(), NOW);
        var first=tours.findByEarliestDeparture(spec,PageRequest.of(0,2),NOW,null);
        var second=tours.findByEarliestDeparture(spec,PageRequest.of(1,2),NOW,null);
        assertEquals(4,first.getTotalElements());
        assertEquals(List.of(b,d),first.map(Tour::getId).getContent());
        assertEquals(List.of(a,c),second.map(Tour::getId).getContent());
    }
    @Test void dateFilterStillCountsEachTourOnce() {
        var request=new TourSearchRequest();request.setDepD(LocalDate.of(2026,11,10));
        var result=tours.findByEarliestDeparture(TourSpecification.build(request, NOW),PageRequest.of(0,10),NOW,request.getDepD());
        assertEquals(2,result.getTotalElements()); assertEquals(List.of(b,d),result.map(Tour::getId).getContent());
    }
    @Test void pastDepartureDateMatchesNoTourBecauseNothingIsBookable() {
        // Tour c has only a 2026-10-01 departure, already gone at NOW; listing it would show no date.
        var request=new TourSearchRequest();request.setDepD(LocalDate.of(2026,10,1));
        assertTrue(tours.findAll(TourSpecification.build(request, NOW)).isEmpty());
        var sorted=tours.findByEarliestDeparture(TourSpecification.build(request, NOW),PageRequest.of(0,10),NOW,request.getDepD());
        assertEquals(0,sorted.getTotalElements()); assertTrue(sorted.getContent().isEmpty());
    }
    @Test void budgetAndPriceSortUseAdultFareEvenWhenOldBasePriceIsStale() {
        var request=new TourSearchRequest();request.setMaxP(new BigDecimal("4000000"));
        var result=tours.findAll(TourSpecification.build(request, NOW),PageRequest.of(0,10,Sort.by("priceView.adultPrice")));
        assertEquals(List.of(c,a),result.map(Tour::getId).getContent());
    }
    @Test void priceSortAndDepartureDateFilterCanBeCombinedWithoutDistinctOrderConflicts() {
        var request=new TourSearchRequest();request.setDepD(LocalDate.of(2026,11,10));
        var result=tours.findAll(TourSpecification.build(request, NOW),PageRequest.of(0,10,Sort.by("priceView.adultPrice")));
        assertEquals(2,result.getTotalElements());assertEquals(List.of(d,b),result.map(Tour::getId).getContent());
    }
    @Test void vehicleFilterUsesStringVehicleIds() {
        var request=new TourSearchRequest();request.setVeh("BUS");
        assertEquals(4,tours.findAll(TourSpecification.build(request, NOW)).size());
        var missing=new TourSearchRequest();missing.setVeh("UNKNOWN");
        assertTrue(tours.findAll(TourSpecification.build(missing, NOW)).isEmpty());
    }
    @Test void searchSnapshotUsesAdultPriceAndOnlyOpenUpcomingSeats() {
        var source=new com.example.tour_service.service.SearchCatalogSnapshot(jdbc,Clock.fixed(NOW.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant(),ZoneId.of("Asia/Ho_Chi_Minh")));
        var snapshot=source.read();assertTrue(snapshot.complete());assertEquals(4,snapshot.total());
        var first=snapshot.tours().stream().filter(t->t.id()==a).findFirst().orElseThrow();
        assertEquals(0,new BigDecimal("3000000").compareTo(first.basePrice()));assertEquals(2,first.departureDates().size());
        jdbc.update("UPDATE prices SET adult_price=4500000 WHERE id=(SELECT price_id FROM tour WHERE id=?)",a);
        jdbc.update("UPDATE tour_departure SET operational_status='SUSPENDED' WHERE tour_id=?",a);
        var updated=source.read().tours().stream().filter(t->t.id()==a).findFirst().orElseThrow();
        assertEquals(0,new BigDecimal("4500000").compareTo(updated.basePrice()));assertTrue(updated.departureDates().isEmpty());
        jdbc.update("DELETE FROM prices WHERE id=(SELECT price_id FROM tour WHERE id=?)",a);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,source::read);
    }
}
