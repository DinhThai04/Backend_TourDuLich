package com.example.booking_service.change;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Keeps customer reschedule requests from blocking a booking after the review window closes. */
@Component
public class ChangeDeadlineWorker {
    private static final Logger log = LoggerFactory.getLogger(ChangeDeadlineWorker.class);
    private final BookingChangeService changes;

    public ChangeDeadlineWorker(BookingChangeService changes) {
        this.changes = changes;
    }

    @Scheduled(fixedDelayString = "${app.booking.change-deadline-poll-ms:60000}", initialDelay = 15000)
    public void sweep() {
        try {
            changes.rejectOverdueCustomerRequests();
        } catch (Exception error) {
            log.warn("Change deadline sweep stopped ({}); will retry", error.getClass().getSimpleName());
        }
    }
}
