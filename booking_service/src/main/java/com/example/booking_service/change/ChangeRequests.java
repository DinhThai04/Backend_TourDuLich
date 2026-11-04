package com.example.booking_service.change;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public final class ChangeRequests {
    private ChangeRequests() {}
    public record Customer(@NotNull String clientKey, @Positive int bookingId, @Positive int targetId,
                           @NotBlank @Size(max=1000) String reason) {}
    public record Shop(@NotNull String clientKey, @Positive int sourceId, @PositiveOrZero long expectedVersion,
                       @NotBlank String reasonCode, @NotBlank @Size(min=20,max=1000) String reason,
                       @NotBlank @Size(min=10,max=1000) String evidence, boolean originalCannotDepart,
                       @NotNull LocalDateTime departureAt, @NotNull LocalDateTime returnAt,
                       @Min(1) @Max(32767) int capacity, @NotNull LocalDateTime responseDeadline) {}
    public record Decision(@NotBlank String action, @PositiveOrZero long expectedVersion,
                           @Size(max=1000) String note, Integer targetId) {}
    public record Refund(@PositiveOrZero long expectedVersion, boolean externalRefundCompleted,
                         @NotNull @DecimalMin("1") BigDecimal amount,
                         @NotBlank @Size(max=191) String reference, @NotBlank @Size(min=10,max=1000) String note) {}
}
