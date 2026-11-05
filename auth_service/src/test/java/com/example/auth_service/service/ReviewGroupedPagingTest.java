package com.example.auth_service.service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.corundumstudio.socketio.SocketIOServer;
import com.example.auth_service.entity.Review;
import com.example.auth_service.mapper.ReviewMapper;
import com.example.auth_service.repository.ReviewRepository;
import com.example.auth_service.repository.UserRepository;
import com.example.auth_service.repository.httpclient.TourCatalogClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The grouped management list pages over tours, so one tour can never land on two pages. */
class ReviewGroupedPagingTest {
    private final ReviewRepository repository = mock(ReviewRepository.class);
    private final TourCatalogClient catalog = mock(TourCatalogClient.class);
    private final ReviewService service = new ReviewService(repository, mock(ReviewMapper.class),
            mock(SocketIOServer.class), mock(UserRepository.class), mock(ReviewEligibility.class),
            catalog, new TransactionTemplate(mock(PlatformTransactionManager.class)));

    private void toursPage(long... tourIds) {
        when(repository.findTourIdsWithReviews(any(Pageable.class))).thenReturn(new PageImpl<>(
                Arrays.stream(tourIds).boxed().toList(), PageRequest.of(0, 2), tourIds.length + 40));
    }

    private Review review(long id, long tourId) {
        return Review.builder().id(id).tourId(tourId).content("tuyet").rating(5)
                .createdAt(LocalDateTime.of(2026, 11, 3, 9, 0)).build();
    }

    @Test
    void aPageHoldsWholeGroupsInTourOrder() {
        toursPage(7L, 8L);
        // Rows arrive newest-first across all page tours; each group still collects all of its own.
        when(repository.findByTourIdInOrderByCreatedAtDesc(eq(List.of(7L, 8L)), any(Pageable.class)))
                .thenReturn(List.of(review(1L, 8L), review(2L, 7L), review(3L, 7L), review(4L, 7L)));
        when(catalog.snapshot()).thenReturn(new TourCatalogClient.Snapshot(List.of(
                new TourCatalogClient.Entry(8, "Da Lat"), new TourCatalogClient.Entry(7, "Phu Quoc"))));

        var groups = service.getAllReviewsGroupedByTour(0, 2);

        assertEquals(List.of(7L, 8L), groups.stream().map(group -> group.getTourId()).toList());
        assertEquals(List.of(2L, 3L, 4L),
                groups.get(0).getReviews().stream().map(item -> item.getId()).toList());
        assertEquals("Phu Quoc", groups.get(0).getTourName());
        assertEquals(1, groups.get(1).getReviews().size());
    }

    @Test
    void requestParametersStayBoundedAndUnsorted() {
        when(repository.findTourIdsWithReviews(any(Pageable.class))).thenReturn(Page.empty());

        service.getAllReviewsGroupedByTour(-3, 10_000);

        // The group-by query owns the ordering; a second ORDER BY would fight the aggregation.
        verify(repository).findTourIdsWithReviews(argThat(page ->
                page.getPageNumber() == 0 && page.getPageSize() == 500 && page.getSort().isUnsorted()));
    }

    @Test
    void emptyPageNeverReachesTheCatalog() {
        when(repository.findTourIdsWithReviews(any(Pageable.class))).thenReturn(Page.empty());

        assertTrue(service.getAllReviewsGroupedByTour(4, 50).isEmpty());

        verifyNoInteractions(catalog);
        verify(repository, never()).findByTourIdInOrderByCreatedAtDesc(any(), any());
    }

    /** The caller warns at 500 review rows, so the query must stop at exactly that many. */
    @Test
    void reviewRowsAreCappedInsteadOfMaterialisingEveryReviewOfThePage() {
        toursPage(7L);
        when(repository.findByTourIdInOrderByCreatedAtDesc(eq(List.of(7L)), any(Pageable.class)))
                .thenReturn(List.of());

        service.getAllReviewsGroupedByTour(0, 2);

        verify(repository).findByTourIdInOrderByCreatedAtDesc(eq(List.of(7L)),
                argThat(page -> page.getPageNumber() == 0 && page.getPageSize() == 500));
    }

    @Test
    void catalogOutageStillRendersThePageWithPlaceholders() {
        when(repository.findTourIdsWithReviews(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(9L), PageRequest.of(1, 200), 9));
        when(repository.findByTourIdInOrderByCreatedAtDesc(eq(List.of(9L)), any(Pageable.class)))
                .thenReturn(List.of(review(5L, 9L)));
        when(catalog.snapshot()).thenThrow(new IllegalStateException("tour service down"));

        var groups = service.getAllReviewsGroupedByTour(1, 200);

        assertEquals(1, groups.size());
        assertEquals("Tour #9", groups.get(0).getTourName());
        assertEquals(1, groups.get(0).getReviews().size());
    }
}
