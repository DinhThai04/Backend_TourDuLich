package com.example.booking_service.controller;

import com.example.booking_service.reservation.BookingTicketMailContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class InternalTicketMailController {
    private final BookingTicketMailContext tickets;

    @GetMapping("/internal/booking/bookings/{id}/ticket-mail")
    public BookingTicketMailContext.Ticket ticket(@PathVariable int id) {
        return tickets.forBooking(id);
    }
}
