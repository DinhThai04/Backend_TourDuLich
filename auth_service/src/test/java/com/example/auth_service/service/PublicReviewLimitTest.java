package com.example.auth_service.service;

import com.example.auth_service.repository.*;
import com.example.auth_service.repository.httpclient.TourCatalogClient;
import com.example.auth_service.mapper.*;
import com.corundumstudio.socketio.SocketIOServer;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class PublicReviewLimitTest {
    final ReviewRepository repository=mock(ReviewRepository.class);
    final UserRepository users=mock(UserRepository.class);
    final ReviewService service=new ReviewService(repository,mock(ReviewMapper.class),mock(SocketIOServer.class),
            users,mock(ReviewEligibility.class),
            mock(TourCatalogClient.class),new TransactionTemplate(mock(PlatformTransactionManager.class)));
    @Test void invalidSizesNeverReachDatabase() {
        for(int size:new int[]{-1,0,21,Integer.MAX_VALUE}) {
            var failure=assertThrows(ResponseStatusException.class,()->service.getHighRatings(size));
            assertEquals(400,failure.getStatusCode().value());
        }
        verifyNoInteractions(repository);
    }
    @Test void defaultAndUpperBoundRemainUsable() {
        when(repository.findByRatingGreaterThanEqual(eq(4),any())).thenReturn(Page.empty());
        service.getHighRatings(null);service.getHighRatings(20);
        verify(repository).findByRatingGreaterThanEqual(eq(4),argThat(page->page.getPageSize()==4));
        verify(repository).findByRatingGreaterThanEqual(eq(4),argThat(page->page.getPageSize()==20));
    }
    @Test void tourReviewPageClampsPageAndSizeInsteadOfFailing() {
        when(repository.findByTourIdAndUserIsNotNullOrderByIdDesc(eq(9L),any(Pageable.class))).thenReturn(Page.empty());
        service.getReviewsByTour(9L,-3,10_000);
        verify(repository).findByTourIdAndUserIsNotNullOrderByIdDesc(eq(9L),argThat(page->page.getPageNumber()==0&&page.getPageSize()==500));
        service.getReviewsByTour(9L,2,0);
        verify(repository).findByTourIdAndUserIsNotNullOrderByIdDesc(eq(9L),argThat(page->page.getPageNumber()==2&&page.getPageSize()==1));
        service.getReviewsByTour(9L,null,null);
        verify(repository).findByTourIdAndUserIsNotNullOrderByIdDesc(eq(9L),argThat(page->page.getPageNumber()==0&&page.getPageSize()==200));
    }
}
