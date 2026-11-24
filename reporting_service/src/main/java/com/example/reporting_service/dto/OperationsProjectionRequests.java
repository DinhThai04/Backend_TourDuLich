package com.example.reporting_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public final class OperationsProjectionRequests {

    private OperationsProjectionRequests() {
    }

    public record Booking(
            @NotNull Integer bookingId,
            @NotNull Long accountId,
            @NotNull Integer departureId,
            @NotNull Integer tourId,
            @Size(max = 40) String tourCode,
            @NotBlank @Size(max = 20) String status,
            @NotNull Integer seats,
            @NotNull Long amount,
            @NotNull LocalDateTime createdAt,
            LocalDateTime confirmedAt,
            LocalDateTime cancelledAt,
            @NotNull LocalDateTime lastUpdated,
            Long refundAmount,
            LocalDateTime refundedAt) {
    }

    public record Payment(
            @NotNull Integer paymentId,
            @NotNull Integer bookingId,
            @NotNull Long accountId,
            @NotBlank @Size(max = 20) String provider,
            @NotBlank @Size(max = 20) String status,
            @NotNull Long amount,
            @Size(max = 80) String txnRef,
            LocalDateTime paidAt,
            @NotNull LocalDateTime createdAt,
            Long refundAmount,
            LocalDateTime refundedAt) {
    }

    public record Invoice(
            @NotNull Integer invoiceId,
            @NotNull Integer bookingId,
            @NotNull Long accountId,
            @NotBlank @Size(max = 20) String status,
            @NotNull Long amount,
            @NotNull LocalDateTime issuedAt,
            LocalDateTime paidAt) {
    }

    public record Tour(
            @NotNull Integer tourId,
            @Size(max = 40) String tourCode,
            @NotNull Integer departureId,
            @NotNull LocalDateTime departureDate,
            Integer capacity,
            Integer availableSeats,
            @Size(max = 20) String operationalStatus,
            Integer bookedSeats,
            Integer heldSeats,
            @NotNull LocalDateTime lastUpdated) {
    }

    public record User(
            @NotNull Long userId,
            @Size(max = 200) String email,
            @NotBlank @Size(max = 20) String status,
            @Size(max = 80) String role,
            Integer totalBookings,
            Long totalSpent,
            LocalDateTime lastActive) {
    }

    public record Refund(
            @NotBlank @Size(max = 36) String refundId,
            @NotNull Integer bookingId,
            @NotBlank @Size(max = 20) String source,
            @NotNull Long amount,
            @NotBlank @Size(max = 20) String status,
            @NotNull LocalDateTime refundedAt,
            @NotNull LocalDateTime createdAt) {
    }
}
