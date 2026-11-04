package com.example.tour_service;

import com.example.tour_service.service.*;
import com.example.tour_service.entity.*;
import com.example.tour_service.dto.request.*;
import com.example.tour_service.dto.request.ApiResponse;
import com.example.tour_service.dto.response.*;
import com.example.tour_service.repository.*;
import com.example.tour_service.repository.httpClient.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TourMutationTest {
    @TempDir Path tempDir;
    private final TourRepository tours=mock(TourRepository.class);
    private final ItineraryRepository itineraries=mock(ItineraryRepository.class);
    private final LocationRepository locations=mock(LocationRepository.class);
    private final VehicleRepository vehicles=mock(VehicleRepository.class);
    private final PricingClient prices=mock(PricingClient.class);
    private final FileClient files=mock(FileClient.class);
    private final TourDepartureService departures=mock(TourDepartureService.class);
    private TourService service;
    private TourImageCache imageCache;
    private UpdateTourRequest request;
    @BeforeEach void setup() {
        imageCache=new TourImageCache(files,tours,new ObjectMapper(),tempDir.resolve("tour-images.json").toString());
        service=new TourService(tours,itineraries,locations,vehicles,prices,files,imageCache,departures,
                new TransactionTemplate(mock(PlatformTransactionManager.class)),mock(org.springframework.jdbc.core.JdbcTemplate.class));
        var location=Location.builder().id(1).city("City").build(); var vehicle=Vehicle.builder().id("BUS").vehicleType("Bus").build();
        var tour=Tour.builder().id(1).basePrice(new BigDecimal("3000000")).tourPriceId(1L).departureLocation(location).destinationLocation(location).vehicle(vehicle).build();
        when(tours.findAllIds()).thenReturn(List.of(1));
        when(tours.findById(1)).thenReturn(Optional.of(tour));when(tours.existsById(1)).thenReturn(true);
        when(tours.lockForItinerary(1)).thenReturn(Optional.of(tour));
        when(tours.save(any())).thenAnswer(call->call.getArgument(0));
        when(locations.findById(1)).thenReturn(Optional.of(location));when(vehicles.findById("BUS")).thenReturn(Optional.of(vehicle));
        when(prices.getPriceById(1L)).thenReturn(ApiResponse.<TourPriceResponse>builder().result(TourPriceResponse.builder().id(1L).adultPrice(new BigDecimal("6000000")).build()).build());
        when(departures.getTourDepartureByTourId(1)).thenReturn(List.of());
        request=UpdateTourRequest.builder().tourProgram("UPDATED").departureLocationId(1).destinationLocationId(1).vehicleId("BUS").tourPriceId(1L).basePrice(BigDecimal.ONE).build();
    }
    @Test void responseAndCompatibilitySnapshotUseAdultFareAndPreserveExistingGallery() {
        when(files.getTourMedia(anyList())).thenReturn(List.of(new TourFileResponse("1","old.jpg")));
        imageCache.refresh();
        var result=service.updateTour(1,request);
        assertEquals(new BigDecimal("6000000"),result.getBasePrice()); assertEquals(List.of("old.jpg"),result.getImageIds()); assertEquals("UPDATED",result.getTourProgram());
        verify(tours).save(argThat(tour->tour.getBasePrice().compareTo(new BigDecimal("6000000"))==0));
    }
    @Test void newFilesAndRemovedLinksAreForwardedTogetherAndReturnedGalleryIsFresh() {
        request.setUrl(List.of("old.jpg"));var file=new MockMultipartFile("files","new.png","image/png",validPng());request.setFiles(List.of(file));
        when(files.editTourImages("1","[\"old.jpg\"]",request.getFiles())).thenReturn(List.of(new TourFileResponse("1","new.jpg")));
        assertEquals(List.of("new.jpg"),service.updateTour(1,request).getImageIds());
        verify(files).editTourImages("1","[\"old.jpg\"]",request.getFiles());
    }
    @Test void creatingTourReturnsUploadedGalleryOrPendingTourIdForSafeRetry() {
        var file=new MockMultipartFile("files","new.png","image/png",validPng());
        var create=TourRequest.builder().tourProgram("TEST").tourTitle("Tour").description("Test").duration(1).departureLocationId(1).destinationLocationId(1).vehicleId("BUS").tourPriceId(1L).files(List.of(file)).build();
        when(tours.save(any())).thenAnswer(call->{Tour tour=call.getArgument(0);tour.setId(1);return tour;});
        doReturn(List.of(new FileResponse("x.png","new.jpg"))).when(files).uploadMultipleFiles("1",List.of(file));
        var complete=service.createTour(create);assertEquals("COMPLETE",complete.getImageUploadStatus());assertEquals(List.of("new.jpg"),complete.getImageIds());
        when(files.uploadMultipleFiles("1",List.of(file))).thenThrow(new IllegalStateException("fixture"));
        var pending=service.createTour(create);assertEquals("PENDING",pending.getImageUploadStatus());assertEquals(1,pending.getId());
        clearInvocations(tours);
        doReturn(List.of(new FileResponse("x.png","new.jpg"))).when(files).uploadMultipleFiles("1",List.of(file));
        assertEquals("COMPLETE",service.retryTourImages(1,List.of(file)).getImageUploadStatus());
        verify(tours,never()).save(any());
    }
    @Test void invalidFileCannotCreateTourBeforeValidation() {
        assertThrows(IllegalArgumentException.class,()->service.createTour(TourRequest.builder().files(List.of(new MockMultipartFile("files","fake.png","image/png",new byte[]{1}))).build()));
        verify(tours,never()).save(any());verifyNoInteractions(files);
    }
    @Test void pricingOutageKeepsTheStoredPriceAndFlagsItAsStale() {
        when(prices.getPriceById(1L)).thenThrow(new IllegalStateException("pricing is down"));
        var degraded=service.getTourById(1);
        assertTrue(degraded.isPriceStale());
        assertEquals(new BigDecimal("3000000"),degraded.getBasePrice());
        doReturn(ApiResponse.<TourPriceResponse>builder()
                .result(TourPriceResponse.builder().id(1L).adultPrice(new BigDecimal("6000000")).build()).build())
                .when(prices).getPriceById(1L);
        var fresh=service.getTourById(1);
        assertFalse(fresh.isPriceStale());
        assertEquals(new BigDecimal("6000000"),fresh.getBasePrice());
    }
    private static byte[] validPng() {
        try { var out=new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",out);
            return out.toByteArray();
        } catch(java.io.IOException exception) { throw new AssertionError(exception); }
    }
}
