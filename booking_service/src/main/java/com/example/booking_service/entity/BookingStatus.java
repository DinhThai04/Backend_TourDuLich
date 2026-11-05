package com.example.booking_service.entity;

/** Full booking lifecycle; legacy rows are mapped by the 2026-10-31 migration. */
public enum BookingStatus {
    PENDING_PAYMENT,
    CONFIRMED,
    CANCELLED,
    EXPIRED
}
