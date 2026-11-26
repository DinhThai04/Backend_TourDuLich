package com.example.booking_service.reservation;

import org.springframework.stereotype.Component;
import java.time.*;
import java.time.temporal.ChronoUnit;

@Component
public class ReservationPolicy {
    @org.springframework.beans.factory.annotation.Value("${app.booking.sales-cutoff:PT4H}")
    private Duration salesCutoff = Duration.ofHours(4);

    public void requireBookable(LocalDateTime now, LocalDateTime departure) {
        if (!now.isBefore(departure.minus(salesCutoff)))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "Đã đóng nhận đặt chỗ cho chuyến này. Vui lòng chọn lịch khởi hành khác.");
    }

    public LocalDateTime capDeadline(LocalDateTime deadline, LocalDateTime departure) {
        return earlier(deadline, departure.minus(salesCutoff));
    }

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
