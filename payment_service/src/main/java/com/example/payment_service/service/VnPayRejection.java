package com.example.payment_service.service;

/**
 * A rejected VNPay callback. Carries the IPN response code so the controller can
 * distinguish "invalid signature" (97), "order not found" (01) and "amount mismatch" (04).
 */
public class VnPayRejection extends IllegalArgumentException {
    private final String rspCode;

    public VnPayRejection(String rspCode, String message) {
        super(message);
        this.rspCode = rspCode;
    }

    public String rspCode() {
        return rspCode;
    }
}
