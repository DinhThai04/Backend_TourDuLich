package com.example.auth_service.repository;


import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.example.auth_service.entity.Review;

import java.util.Collection;
import java.util.List;

public interface ReviewRepository extends JpaRepository<Review, Long> {
    boolean existsByUserIdAndTourId(Long userId, Long tourId);

    /**
     * The management list groups reviews by tour, so paging walks tours instead of raw rows: a page of
     * rows splits one tour's reviews across pages and shows that group twice. Newest activity first.
     * The list renders whole groups without a total, so a slice skips the count(distinct) scan.
     */
    @Query("select r.tourId from Review r group by r.tourId order by max(r.createdAt) desc")
    Slice<Long> findTourIdsWithReviews(Pageable pageable);

    @EntityGraph(attributePaths = "user")
    List<Review> findByTourIdInOrderByCreatedAtDesc(Collection<Long> tourIds, org.springframework.data.domain.Pageable pageable);

    /** Public tour feed: bounded page, newest review first, authors only so pages stay full. */
    @EntityGraph(attributePaths = "user")
    Page<Review> findByTourIdAndUserIsNotNullOrderByIdDesc(Long tourId, Pageable pageable);


    @EntityGraph(attributePaths = "user")
    Page<Review> findByRatingGreaterThanEqual(int minRating, Pageable pageable);


}
