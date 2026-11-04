package com.example.auth_service.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.corundumstudio.socketio.SocketIOServer;
import com.example.auth_service.dto.request.ReviewRequest;
import com.example.auth_service.dto.response.ReviewGroupResponse;
import com.example.auth_service.dto.response.ReviewResponse;
import com.example.auth_service.dto.response.UserResponse;
import com.example.auth_service.entity.Review;
import com.example.auth_service.entity.User;
import com.example.auth_service.mapper.ReviewMapper;
import com.example.auth_service.paging.PageSlice;
import com.example.auth_service.repository.ReviewRepository;
import com.example.auth_service.repository.UserRepository;
import com.example.auth_service.repository.httpclient.TourCatalogClient;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ReviewService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ReviewService.class);
    private static final int DEFAULT_TOUR_REVIEW_SIZE = 200;
    private static final int MAX_TOUR_REVIEW_SIZE = 500;

    private final ReviewRepository reviewRepository;
    private final ReviewMapper reviewMapper;
    private final SocketIOServer socketIOServer;
    private final UserRepository userRepository;
    private final ReviewEligibility eligibility;
    private final TourCatalogClient tourCatalogClient;
    private final TransactionTemplate transactions;

    public com.example.auth_service.repository.httpclient.BookingReviewClient.Eligibility eligibility(long tourId) {
        var result = eligibility.check(tourId);
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        long id;
        try {
            if (authentication == null || !authentication.isAuthenticated()) throw new NumberFormatException();
            id = Long.parseLong(authentication.getName());
        } catch (NumberFormatException invalid) {
            throw new org.springframework.security.access.AccessDeniedException("Cần đăng nhập để đánh giá");
        }
        return reviewRepository.existsByUserIdAndTourId(id, tourId)
            ? new com.example.auth_service.repository.httpclient.BookingReviewClient.Eligibility(false,"Bạn đã đánh giá tour này. Mỗi tài khoản được gửi một đánh giá cho mỗi tour.") : result;
    }
    public ReviewResponse createReview(ReviewRequest req) {
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new org.springframework.security.access.AccessDeniedException("Cần đăng nhập để đánh giá");
        }
        Long authorId;
        try { authorId = Long.valueOf(authentication.getName()); }
        catch (NumberFormatException ex) { throw new org.springframework.security.access.AccessDeniedException("Tài khoản không hợp lệ"); }

        // Ask booking-service before opening a transaction, so no DB connection waits on HTTP.
        var allowed=eligibility.check(req.getTourId());
        if(!allowed.eligible()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,allowed.message());

        ReviewResponse response = transactions.execute(status -> {
            // Serialize posts for this owner, including concurrent requests in different application instances.
            User author = userRepository.lockForReview(authorId)
                    .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("Tài khoản không tồn tại"));
            if(reviewRepository.existsByUserIdAndTourId(authorId,req.getTourId()))
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"Bạn đã đánh giá tour này.");
            Review review = Review.builder()
                    .tourId(req.getTourId())
                    .user(author)
                    .content(req.getContent().trim())
                    .rating(req.getRating())
                    .build();

            return reviewMapper.toResponse(reviewRepository.save(review));
        });

        // The transaction above is committed; realtime delivery stays best-effort because
        // the HTTP response is the durable source and clients can reload.
        try { socketIOServer.getRoomOperations("tour_" + req.getTourId()).sendEvent("new_review", response); }
        catch (RuntimeException unavailable) { /* see comment above */ }

        return response;
    }

    public List<ReviewResponse> getReviewsByTour(Long tourId, Integer page, Integer size) {
        int safePage = page == null ? 0 : Math.max(page, 0);
        int safeSize = size == null ? DEFAULT_TOUR_REVIEW_SIZE
                : Math.max(1, Math.min(size, MAX_TOUR_REVIEW_SIZE));

        List<Review> reviews = reviewRepository
                .findByTourIdAndUserIsNotNullOrderByIdDesc(tourId, PageRequest.of(safePage, safeSize))
                .getContent();

        // One user query for the whole page instead of one lookup per review, then reuse the
        // avatar pointer already stored on the user row instead of calling file-service per author.
        Set<Long> authorIds = new HashSet<>();
        for (Review review : reviews) {
            if (review.getUser() != null) {
                authorIds.add(review.getUser().getId());
            }
        }
        Map<Long, User> authors = authorIds.isEmpty()
                ? Map.of()
                : userRepository.findAllById(authorIds).stream()
                        .collect(Collectors.toMap(User::getId, author -> author, (first, second) -> first));

        List<ReviewResponse> responseList = new ArrayList<>(reviews.size());
        for (Review review : reviews) {
            // A review whose author row disappeared cannot be rendered by public pages.
            User author = review.getUser() == null ? null : authors.get(review.getUser().getId());
            if (author == null) continue;
            review.setUser(author);
            responseList.add(reviewMapper.toResponse(review));
        }
        return responseList;
    }

    /**
     * The management list shows one card per tour, so a page is a page of tours. Paging review rows
     * instead would split one tour's reviews across two requests and render that tour twice.
     */
    public List<ReviewGroupResponse> getAllReviewsGroupedByTour(Integer page, Integer size) {
        Pageable slice = PageSlice.of(page, size).pageable(Sort.unsorted());
        List<Long> tourIds = reviewRepository.findTourIdsWithReviews(slice).getContent();
        if (tourIds.isEmpty()) return List.of();

        // Remote catalogue lookup happens after the database work so a catalog outage still
        // renders the page with placeholder titles.
        Map<Long, String> tourNames = loadTourNames(tourIds);
        Map<Long, ReviewGroupResponse> grouped = new LinkedHashMap<>();
        for (Long tourId : tourIds) {
            grouped.put(tourId, new ReviewGroupResponse(tourId,
                    tourNames.getOrDefault(tourId, "Tour #" + tourId), new ArrayList<>()));
        }

        // Newest-first over the whole page of tours, hard-capped: the caller shows its own truncation
        // warning at this size, and an uncapped IN list would materialise every review of 500 tours.
        for (Review review : reviewRepository.findByTourIdInOrderByCreatedAtDesc(
                tourIds, PageRequest.of(0, MAX_TOUR_REVIEW_SIZE))) {
            ReviewGroupResponse group = grouped.get(review.getTourId());
            // A tour that lost its last review between the two queries has no card left.
            if (group == null) continue;
            User user = review.getUser();
            group.getReviews().add(new ReviewResponse(
                    review.getId(),
                    review.getTourId(),
                    adminAuthor(user),
                    review.getContent(),
                    review.getRating(),
                    review.getCreatedAt() != null ? review.getCreatedAt().toString() : null,
                    user == null ? null : user.getUsername(),
                    user == null ? null : user.getAvatar()
            ));
        }
        return new ArrayList<>(grouped.values());
    }

    private Map<Long, String> loadTourNames(Collection<Long> pageTourIds) {
        try {
            var snapshot = tourCatalogClient.snapshot();
            if (snapshot == null || snapshot.tours() == null) return Map.of();
            Set<Long> wanted = new HashSet<>(pageTourIds);
            Map<Long, String> names = new LinkedHashMap<>();
            for (var tour : snapshot.tours()) {
                if (tour == null || !wanted.contains((long) tour.id())) continue;
                if (tour.tourTitle() != null && !tour.tourTitle().isBlank())
                    names.put((long) tour.id(), tour.tourTitle());
            }
            return names;
        } catch (RuntimeException unavailable) {
            log.warn("Tour catalogue unavailable ({}); the page renders placeholder titles", unavailable.getMessage(), unavailable);
            return Map.of();
        }
    }

    private UserResponse adminAuthor(User user) {
        if (user == null) return null;
        return UserResponse.builder()
                .id(String.valueOf(user.getId()))
                .username(user.getUsername())
                .email(user.getEmail())
                .phone(user.getPhone())
                .address(user.getAddress())
                .avatar(user.getAvatar())
                .status(user.getStatus())
                .build();
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<ReviewResponse> getHighRatings(Integer size) {
        if (size == null) size = 4;
        if (size < 1 || size > 20)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"Số đánh giá phải từ 1 đến 20");

        // 1. Tạo một đối tượng Pageable để yêu cầu database
        // - Trang 0 (trang đầu tiên)
        // - 'size' (số lượng bản ghi mong muốn)
        // - Sắp xếp: ưu tiên 1 là "rating" giảm dần (DESC), ưu tiên 2 là "createdAt" giảm dần
        Pageable pageable = PageRequest.of(
                0,
                size,
                Sort.by("rating").descending().and(Sort.by("createdAt").descending())
        );

        // 2. Gọi repository với Pageable
        // Giả sử bạn muốn lấy các đánh giá có rating >= 4
        // (Nếu bạn không có phương thức này, bạn có thể dùng `reviewRepository.findAll(pageable)`)
        Page<Review> reviewPage = reviewRepository.findByRatingGreaterThanEqual(4, pageable);

        // 3. Chuyển đổi Page<Review> thành List<ReviewResponse>

        return reviewPage.getContent().stream()
                .filter(review -> review.getUser() != null)
                .map(reviewMapper::toResponse)
                .collect(Collectors.toList());

    }
}
