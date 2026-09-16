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

    @Mapping(target = "user", source = "user")
    @Mapping(target = "createdAt", source = "createdAt", qualifiedByName = "formatDateTime")
    ReviewResponse toResponse(Review review);

    List<ReviewResponse> toResponseList(List<Review> reviews);

    // ✅ Hàm custom định dạng ngày giờ
    @Named("formatDateTime")
    default String formatDateTime(java.time.LocalDateTime dateTime) {
        if (dateTime == null)
            return null;
        return dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}