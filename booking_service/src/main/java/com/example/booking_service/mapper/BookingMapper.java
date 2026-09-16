package com.example.booking_service.mapper;

import com.example.booking_service.dto.request.BookingRequest;
import com.example.booking_service.dto.response.BookingResponse;
import com.example.booking_service.entity.Booking;
import com.example.booking_service.entity.Customer;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring")
public interface BookingMapper {
    Booking toBooking(BookingRequest request);
    BookingResponse toBookingResponse(Booking booking, List<Customer> listOfCustomers);
    BookingResponse toBookingResponse(Booking booking);
    java.util.List<BookingResponse> toBookingResponseList(java.util.List<Booking> bookings);
}