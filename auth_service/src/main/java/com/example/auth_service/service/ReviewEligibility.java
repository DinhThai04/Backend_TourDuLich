package com.example.auth_service.service;

import com.example.auth_service.repository.httpclient.BookingReviewClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class ReviewEligibility {
    private final BookingReviewClient bookings;
    public ReviewEligibility(BookingReviewClient bookings) { this.bookings=bookings; }
    public BookingReviewClient.Eligibility check(long tourId) {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(!(auth instanceof JwtAuthenticationToken jwt)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Vui lòng đăng nhập để đánh giá");
        try {
            var response=bookings.check(tourId,"Bearer "+jwt.getToken().getTokenValue());
            if(response==null || response.getCode()!=1000 || response.getResult()==null)
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Chưa xác minh được điều kiện đánh giá. Vui lòng thử lại.");
            return response.getResult();
        } catch(feign.FeignException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Chưa xác minh được điều kiện đánh giá. Vui lòng thử lại.");
        }
    }
}
