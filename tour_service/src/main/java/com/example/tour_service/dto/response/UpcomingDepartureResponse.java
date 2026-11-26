package com.example.tour_service.dto.response;

import java.time.LocalDateTime;

public record UpcomingDepartureResponse(int id, int tourId, String tourTitle, String tourCode,
                                        LocalDateTime departureDate, Short availableSeats, Integer capacity) {}
