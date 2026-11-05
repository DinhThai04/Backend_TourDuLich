package com.example.auth_service.service;
import com.example.auth_service.entity.*;
import com.example.auth_service.repository.*;
import com.example.auth_service.mapper.*;
import com.example.auth_service.dto.request.ReviewRequest;
import com.example.auth_service.dto.response.ReviewGroupResponse;
import com.example.auth_service.dto.response.ReviewResponse;
import com.example.auth_service.repository.httpclient.*;
import com.corundumstudio.socketio.SocketIOServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
@DataJpaTest(properties={"spring.jpa.database-platform=org.hibernate.dialect.H2Dialect","spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect","spring.jpa.hibernate.ddl-auto=create-drop"})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class ReviewPersistenceTest {
 @Autowired UserRepository users; @Autowired ReviewRepository reviews; @Autowired PlatformTransactionManager manager;
 @Test void concurrentPostsCreateOnlyOneReviewForSameOwnerAndTour() throws Exception {
  var tx=new TransactionTemplate(manager);
  long id=tx.execute(s->users.saveAndFlush(User.builder().username("reviewer").email("review@example.test").password("test-only").build()).getId());
  var eligibility=mock(ReviewEligibility.class);when(eligibility.check(1L)).thenReturn(new BookingReviewClient.Eligibility(true,"ok"));
  var mapper=mock(ReviewMapper.class);when(mapper.toResponse(any())).thenReturn(new ReviewResponse());
  var service=new ReviewService(reviews,mapper,mock(SocketIOServer.class,RETURNS_DEEP_STUBS),users,eligibility,mock(TourCatalogClient.class),new TransactionTemplate(mock(PlatformTransactionManager.class)));
  var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
  Callable<Integer> post=()->{start.await();SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(""+id,"",List.of()));
   try{return tx.execute(s->{var request=new ReviewRequest();request.setTourId(1L);request.setContent("Completed trip");request.setRating(5);service.createReview(request);return 200;});}
   catch(ResponseStatusException e){return e.getStatusCode().value();}finally{SecurityContextHolder.clearContext();}};
  try{var first=pool.submit(post);var second=pool.submit(post);start.countDown();var outcomes=List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS));
   assertTrue(outcomes.contains(200));assertTrue(outcomes.contains(409));
   assertEquals(1,reviews.findByTourIdInOrderByCreatedAtDesc(List.of(1L),org.springframework.data.domain.PageRequest.of(0,2)).size());
  }finally{pool.shutdownNow();}
 }
 /** Proves the grouped list pages over tours at the database level: one tour per page, newest activity first. */
 @Test void groupedPagingServesWholeTourGroups() {
  // The other test in this class writes a review stamped with the current time, which would
  // otherwise outrank the fixed timestamps below and decide which tour lands on page one.
  reviews.deleteAll();
  long authorId=users.saveAndFlush(User.builder().username("grouper").email("group@example.test").password("test-only").build()).getId();
  var author=users.findById(authorId).orElseThrow();
  review(11L,"older",LocalDateTime.of(2026,11,1,8,0),author);
  review(12L,"newest",LocalDateTime.of(2026,11,3,8,0),author);
  review(12L,"middle",LocalDateTime.of(2026,11,2,8,0),author);
  var service=new ReviewService(reviews,mock(ReviewMapper.class),mock(SocketIOServer.class),users,
          mock(ReviewEligibility.class),mock(TourCatalogClient.class),new TransactionTemplate(mock(PlatformTransactionManager.class)));

  var firstPage=service.getAllReviewsGroupedByTour(0,1);
  assertEquals(List.of(12L),firstPage.stream().map(ReviewGroupResponse::getTourId).toList());
  assertEquals(List.of("newest","middle"),firstPage.get(0).getReviews().stream().map(ReviewResponse::getContent).toList());
  assertEquals("Tour #12",firstPage.get(0).getTourName());

  var secondPage=service.getAllReviewsGroupedByTour(1,1);
  assertEquals(List.of(11L),secondPage.stream().map(ReviewGroupResponse::getTourId).toList());
  assertEquals(List.of("older"),secondPage.get(0).getReviews().stream().map(ReviewResponse::getContent).toList());
 }
 /** @PrePersist stamps the creation time, so the update below pins the ordering the page must show. */
 private void review(long tourId,String content,LocalDateTime when,User author) {
  var saved=reviews.save(Review.builder().tourId(tourId).user(author).content(content).rating(5).build());
  saved.setCreatedAt(when);
  reviews.saveAndFlush(saved);
 }
}
