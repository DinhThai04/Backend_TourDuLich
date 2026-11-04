package com.example.booking_service.reservation;

import org.springframework.stereotype.Component;
import java.time.*;
import java.time.temporal.ChronoUnit;

@Component
public class ReservationPolicy {
    public record Plan(boolean immediate, LocalDateTime deadline, LocalDateTime reminderOne, LocalDateTime reminderTwo) {}
    public Plan plan(LocalDateTime created, LocalDateTime departure) {
        if (!departure.isAfter(created)) throw new IllegalArgumentException("Chuyến đi đã khởi hành");
        long days = ChronoUnit.DAYS.between(created.toLocalDate(), departure.toLocalDate());
        if (days <= 7) return new Plan(true, earlier(created.plusMinutes(15), departure), null, null);
        LocalDate deadlineDate = created.toLocalDate().plusDays(6);
        LocalDate latest = departure.toLocalDate().minusDays(6);
        if (latest.isBefore(deadlineDate)) deadlineDate = latest;
        LocalDateTime deadline = deadlineDate.atTime(8, 0);
        LocalDateTime lastReminder = deadline.minusDays(1);
        LocalDateTime firstReminder = created.toLocalDate().plusDays(3).atTime(8, 0);
        if (firstReminder.isAfter(lastReminder)) firstReminder = null;
        return new Plan(false, deadline, firstReminder,
                lastReminder.equals(firstReminder) ? null : lastReminder);
    }
    private static LocalDateTime earlier(LocalDateTime a, LocalDateTime b) { return a.isBefore(b) ? a : b; }
}
