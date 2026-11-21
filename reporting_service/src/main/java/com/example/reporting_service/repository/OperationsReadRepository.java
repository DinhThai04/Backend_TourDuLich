package com.example.reporting_service.repository;

import com.example.reporting_service.dto.OperationsProjectionRequests;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Repository
public class OperationsReadRepository {

    private final JdbcTemplate jdbc;

    public OperationsReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record OperationsReport(
            @JsonProperty("bookings") long bookings,
            @JsonProperty("expired") long expired,
            @JsonProperty("collected") long collected,
            @JsonProperty("schedule_refunds") long scheduleRefunds,
            @JsonProperty("reconciliation_refunds") long reconciliationRefunds,
            @JsonProperty("confirmed_passengers") long confirmedPassengers) {
    }

    public void upsert(OperationsProjectionRequests.Booking request) {
        jdbc.update("""
                INSERT INTO ops_booking(booking_id,account_id,departure_id,tour_id,tour_code,status,seats,amount,
                    created_at,confirmed_at,cancelled_at,last_updated,refund_amount,refunded_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE account_id=VALUES(account_id),departure_id=VALUES(departure_id),
                    tour_id=VALUES(tour_id),tour_code=VALUES(tour_code),status=VALUES(status),seats=VALUES(seats),
                    amount=VALUES(amount),confirmed_at=VALUES(confirmed_at),cancelled_at=VALUES(cancelled_at),
                    last_updated=VALUES(last_updated),refund_amount=VALUES(refund_amount),refunded_at=VALUES(refunded_at)
                """, request.bookingId(), request.accountId(), request.departureId(), request.tourId(),
                request.tourCode(), request.status(), request.seats(), request.amount(), request.createdAt(),
                request.confirmedAt(), request.cancelledAt(), request.lastUpdated(), request.refundAmount(),
                request.refundedAt());
    }

    public void upsert(OperationsProjectionRequests.Payment request) {
        jdbc.update("""
                INSERT INTO ops_payment(payment_id,booking_id,account_id,provider,status,amount,txn_ref,paid_at,
                    created_at,refund_amount,refunded_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE booking_id=VALUES(booking_id),account_id=VALUES(account_id),
                    provider=VALUES(provider),status=VALUES(status),amount=VALUES(amount),txn_ref=VALUES(txn_ref),
                    paid_at=VALUES(paid_at),refund_amount=VALUES(refund_amount),refunded_at=VALUES(refunded_at)
                """, request.paymentId(), request.bookingId(), request.accountId(), request.provider(),
                request.status(), request.amount(), request.txnRef(), request.paidAt(), request.createdAt(),
                request.refundAmount(), request.refundedAt());
    }

    public void upsert(OperationsProjectionRequests.Invoice request) {
        jdbc.update("""
                INSERT INTO ops_invoice(invoice_id,booking_id,account_id,status,amount,issued_at,paid_at)
                VALUES (?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE booking_id=VALUES(booking_id),account_id=VALUES(account_id),
                    status=VALUES(status),amount=VALUES(amount),issued_at=VALUES(issued_at),paid_at=VALUES(paid_at)
                """, request.invoiceId(), request.bookingId(), request.accountId(), request.status(),
                request.amount(), request.issuedAt(), request.paidAt());
    }

    public void upsert(OperationsProjectionRequests.Tour request) {
        jdbc.update("""
                INSERT INTO ops_tour(tour_id,tour_code,departure_id,departure_date,capacity,available_seats,
                    operational_status,booked_seats,held_seats,last_updated)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE tour_code=VALUES(tour_code),departure_date=VALUES(departure_date),
                    capacity=VALUES(capacity),available_seats=VALUES(available_seats),
                    operational_status=VALUES(operational_status),booked_seats=VALUES(booked_seats),
                    held_seats=VALUES(held_seats),last_updated=VALUES(last_updated)
                """, request.tourId(), request.tourCode(), request.departureId(), request.departureDate(),
                request.capacity(), request.availableSeats(), request.operationalStatus(), request.bookedSeats(),
                request.heldSeats(), request.lastUpdated());
    }

    public void upsert(OperationsProjectionRequests.User request) {
        jdbc.update("""
                INSERT INTO ops_user(user_id,email,status,role,total_bookings,total_spent,last_active)
                VALUES (?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE email=VALUES(email),status=VALUES(status),role=VALUES(role),
                    total_bookings=VALUES(total_bookings),total_spent=VALUES(total_spent),
                    last_active=VALUES(last_active)
                """, request.userId(), request.email(), request.status(), request.role(),
                request.totalBookings(), request.totalSpent(), request.lastActive());
    }

    public void upsert(OperationsProjectionRequests.Refund request) {
        jdbc.update("""
                INSERT INTO ops_refund(refund_id,booking_id,source,amount,status,refunded_at,created_at)
                VALUES (?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE booking_id=VALUES(booking_id),source=VALUES(source),amount=VALUES(amount),
                    status=VALUES(status),refunded_at=VALUES(refunded_at)
                """, request.refundId(), request.bookingId(), request.source(), request.amount(),
                request.status(), request.refundedAt(), request.createdAt());
    }

    public OperationsReport report(LocalDate from, LocalDate through) {
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime until = through.plusDays(1).atStartOfDay();
        return jdbc.queryForObject("""
                SELECT
                  (SELECT COUNT(*) FROM ops_booking WHERE created_at>=? AND created_at<?) AS bookings,
                  (SELECT COUNT(*) FROM ops_booking
                    WHERE status='EXPIRED' AND created_at>=? AND created_at<?) AS expired,
                  (SELECT COALESCE(SUM(amount),0) FROM ops_payment
                    WHERE status IN ('PAID','REFUNDED') AND paid_at>=? AND paid_at<?) AS collected,
                  (SELECT COALESCE(SUM(amount),0) FROM ops_refund
                    WHERE source='SCHEDULE' AND status='REFUNDED' AND refunded_at>=? AND refunded_at<?)
                    AS schedule_refunds,
                  (SELECT COALESCE(SUM(amount),0) FROM ops_refund
                    WHERE source='RECONCILIATION' AND status='REFUNDED' AND refunded_at>=? AND refunded_at<?)
                    AS reconciliation_refunds,
                  (SELECT COALESCE(SUM(b.seats),0) FROM ops_booking b
                    JOIN ops_tour t ON t.tour_id=b.tour_id AND t.departure_id=b.departure_id
                    JOIN ops_invoice i ON i.booking_id=b.booking_id
                    WHERE b.status='CONFIRMED' AND i.status='PAID' AND t.departure_date>=? AND t.departure_date<?)
                    AS confirmed_passengers
                """, (rs, rowNum) -> new OperationsReport(
                rs.getLong("bookings"),
                rs.getLong("expired"),
                rs.getLong("collected"),
                rs.getLong("schedule_refunds"),
                rs.getLong("reconciliation_refunds"),
                rs.getLong("confirmed_passengers")),
                start, until, start, until, start, until, start, until, start, until, start, until);
    }
}
