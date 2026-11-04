package com.example.booking_service.reservation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReservationExpiryWorker {
    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryWorker.class);
    private final ReservationExpiryService expiry;
    public ReservationExpiryWorker(ReservationExpiryService expiry) { this.expiry = expiry; }
    @Scheduled(fixedDelayString = "${app.booking.expiry-poll-ms:60000}", initialDelay = 5000)
    public void sweep() {
        try { expiry.sweep(); }
        catch (Exception error) { log.warn("Reservation expiry stopped ({}); check booking_reservation schema", error.getClass().getSimpleName()); }
    }
}
