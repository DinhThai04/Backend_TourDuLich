package com.example.booking_service.notification;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class ReminderRules {
    public record Selection(MailEventType type, LocalDateTime due, Integer daysBefore, LocalDateTime deadline) {}
    public Optional<Selection> select(ReminderStateReader.State state, LocalDateTime now) {
        boolean pending = "PENDING_PAYMENT".equals(state.bookingStatus());
        boolean confirmed = "CONFIRMED".equals(state.bookingStatus());
        if ((!pending && !confirmed) || !state.reservationMatches() || state.reconciliation() || state.passengers() < 1
                || state.createdAt() == null || !state.departureAt().toLocalDate().atStartOfDay().isAfter(now)) return Optional.empty();
        var choices = new ArrayList<Selection>();
        LocalDateTime eligibleSince = state.createdAt();
        if (state.allPaidCount() == 0) {
            if (!pending && !confirmed) return Optional.empty();
            if (!"HELD".equals(state.reservationStatus()) || state.deadline() == null || !state.deadline().isAfter(now)) return Optional.empty();
            if (state.reminderOne() != null) choices.add(new Selection(MailEventType.PAYMENT_REMINDER,state.reminderOne(),null,state.deadline()));
            if (state.reminderTwo() != null && !state.reminderTwo().equals(state.reminderOne()))
                choices.add(new Selection(MailEventType.PAYMENT_REMINDER,state.reminderTwo(),null,state.deadline()));
        } else {
            if (!confirmed) return Optional.empty();
            if (state.paymentCount() != 1 || state.invoiceCount() != 1 || state.paidAt() == null
                    || state.paymentAmount() == null || state.invoiceAmount() == null
                    || state.paymentAmount().signum() <= 0 || state.paymentAmount().compareTo(state.invoiceAmount()) != 0
                    || (state.reservationStatus() != null && !"PAID".equals(state.reservationStatus()))) return Optional.empty();
            if (state.paidAt().isAfter(eligibleSince)) eligibleSince = state.paidAt();
            for (int days : new int[]{5,3,1}) choices.add(new Selection(MailEventType.DEPARTURE_REMINDER,
                    state.departureAt().toLocalDate().minusDays(days).atTime(8,0),days,null));
        }
        final LocalDateTime since = eligibleSince;
        choices.removeIf(c -> c.due().isBefore(since));
        choices.sort(Comparator.comparing(Selection::due));
        // On a newer milestone's morning, do not send an old catch-up just before the new 08:00 email.
        if (choices.stream().anyMatch(c -> c.due().toLocalDate().equals(now.toLocalDate()) && c.due().isAfter(now)))
            return Optional.empty();
        return choices.stream().filter(c -> !c.due().isAfter(now)).max(Comparator.comparing(Selection::due));
    }
    public String key(ReminderStateReader.State state, Selection selection) {
        var f = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
        return "booking:" + state.bookingId() + ":reminder:" + selection.type().name() + ":" + state.departureId()
                + ":" + state.departureAt().format(f) + ":" + selection.due().format(f)
                + (selection.deadline()==null ? "" : ":h"+selection.deadline().format(f));
    }
}
