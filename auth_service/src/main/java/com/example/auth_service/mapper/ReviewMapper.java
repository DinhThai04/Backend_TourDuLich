package com.example.auth_service.mapper;

import java.time.format.DateTimeFormatter;
import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import com.example.auth_service.dto.response.ReviewResponse;
import com.example.auth_service.entity.Review;

@Mapper(componentModel = "spring")
public interface ReviewMapper {

    @Mapping(target = "user", source = "user", qualifiedByName = "publicAuthor")
    @Mapping(target = "createdAt", source = "createdAt", qualifiedByName = "formatDateTime")
    ReviewResponse toResponse(Review review);

    List<ReviewResponse> toResponseList(List<Review> reviews);

    @Named("publicAuthor")
    default com.example.auth_service.dto.response.UserResponse publicAuthor(com.example.auth_service.entity.User user) {
        if (user == null) return null;
        return com.example.auth_service.dto.response.UserResponse.builder()
                .id(String.valueOf(user.getId())).username(user.getUsername()).avatar(user.getAvatar()).build();
    }

    // ISO-8601 so browsers (Safari included) can parse it with new Date().
    @Named("formatDateTime")
    default String formatDateTime(java.time.LocalDateTime dateTime) {
        if (dateTime == null)
            return null;
        return dateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
}