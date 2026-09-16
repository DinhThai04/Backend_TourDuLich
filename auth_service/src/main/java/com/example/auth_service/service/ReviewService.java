package com.example.auth_service.service;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.corundumstudio.socketio.SocketIOServer;
import com.example.auth_service.dto.FileInfo;
import com.example.auth_service.dto.request.ReviewRequest;
import com.example.auth_service.dto.response.ReviewGroupResponse;
import com.example.auth_service.dto.response.ReviewResponse;
import com.example.auth_service.dto.response.UserResponse;
import com.example.auth_service.entity.Review;
import com.example.auth_service.entity.User;
import com.example.auth_service.mapper.ReviewMapper;
import com.example.auth_service.mapper.UserMapper;
import com.example.auth_service.repository.ReviewRepository;
import com.example.auth_service.repository.UserRepository;
import com.example.auth_service.repository.httpclient.FileServiceClient;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ReviewService {
    private final ReviewRepository reviewRepository;
    private final ReviewMapper reviewMapper;
    private final SocketIOServer socketIOServer;
    private final UserRepository userRepository;
    private final FileServiceClient fileServiceClient;
    private final UserMapper userMapper;
    public ReviewResponse createReview(ReviewRequest req) {
        Review review = Review.builder()
                .tourId(req.getTourId())
                .user(User.builder().id(req.getUserId()).username(req.getUsername()).avatar(req.getAvatar()).build())
                .content(req.getContent())
                .rating(req.getRating())
                .build();

        reviewRepository.save(review);

        ReviewResponse response = reviewMapper.toResponse(review);

        // ⚡️ Phát realtime đến đúng room mà frontend join
        socketIOServer.getRoomOperations("tour_" + req.getTourId())
                .sendEvent("new_review", response);

        return response;
    }

public List<ReviewResponse> getReviewsByTour(Long tourId) {
    List<Review> reviews = reviewRepository.findByTourIdOrderByCreatedAtDesc(tourId);

    // Duyệt từng review để xử lý avatar
    List<ReviewResponse> responseList = new ArrayList<>();
    for (Review review : reviews) {
        User user = review.getUser();
        UserResponse userResponse = userMapper.toUserResponse(user);
        try {
            FileInfo fileInfo = fileServiceClient.getFileByOwnerId(String.valueOf(user.getId()));
            System.err.println(fileInfo);
            if (fileInfo != null && fileInfo.getUrl() != null) {
                userResponse.setAvatar(fileInfo.getUrl());
            } else {
                userResponse.setAvatar(null);
            }
        } catch (Exception e) {
            userResponse.setAvatar(null);
        }

        ReviewResponse reviewResponse = reviewMapper.toResponse(review);
        reviewResponse.setUser(userResponse);
        responseList.add(reviewResponse);
    }

    return responseList;
}

public List<ReviewGroupResponse> getAllReviewsGroupedByTour() {
    List<Review> allReviews = reviewRepository.findAllByOrderByCreatedAtDesc();
    Map<Long, ReviewGroupResponse> grouped = new LinkedHashMap<>();

    for (Review review : allReviews) {
        Long tourId = review.getTourId();
     
        User user = review.getUser();
        UserResponse userResponse = new UserResponse();
        if (user != null) {
          
            userResponse.setUsername(user.getUsername());
            userResponse.setEmail(user.getEmail());
            userResponse.setAvatar(user.getAvatar());
        }

        // Tạo ReviewResponse
        ReviewResponse reviewRes = new ReviewResponse(
                review.getId(),
                tourId,
                userResponse,
                review.getContent(),
                review.getRating(),
                review.getCreatedAt() != null ? review.getCreatedAt().toString() : null,
                review.getUser().getUsername(),
                review.getUser().getAvatar() != null ? review.getUser().getAvatar() : null
        );

        // Nếu chưa có nhóm tour này → tạo mới
        grouped.computeIfAbsent(tourId, id -> new ReviewGroupResponse(id, "abc", new ArrayList<>()));

        // Thêm review vào nhóm tour tương ứng
        grouped.get(tourId).getReviews().add(reviewRes);
    }

    return new ArrayList<>(grouped.values());
}

    public List<ReviewResponse> getHighRatings(Integer size) {

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
                .map(reviewMapper::toResponse) // Hoặc logic chuyển đổi của bạn
                .collect(Collectors.toList());

    }
}
