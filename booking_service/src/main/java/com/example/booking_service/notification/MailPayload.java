package com.example.booking_service.notification;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Immutable event snapshot. Never include passwords, JWTs or provider secrets. */
public record MailPayload(int version, String customerName, String tourTitle,
        Integer departureId, LocalDateTime departureAt, int passengerCount,
        BigDecimal amountVnd, String transactionRef, LocalDateTime paidAt,
        LocalDateTime holdUntil, Integer daysBefore, LocalDateTime reminderAt, Long changeId,
        LocalDateTime oldDepartureAt, String changeReason, String changeStatus, String refundReference) {
    public MailPayload(int version,String customerName,String tourTitle,Integer departureId,LocalDateTime departureAt,
            int passengerCount,BigDecimal amountVnd,String transactionRef,LocalDateTime paidAt,LocalDateTime holdUntil,Integer daysBefore,LocalDateTime reminderAt) {
        this(version,customerName,tourTitle,departureId,departureAt,passengerCount,amountVnd,transactionRef,paidAt,holdUntil,daysBefore,reminderAt,null,null,null,null,null);
    }
    public MailPayload(int version,String customerName,String tourTitle,Integer departureId,LocalDateTime departureAt,
            int passengerCount,BigDecimal amountVnd,String transactionRef,LocalDateTime paidAt,LocalDateTime holdUntil,Integer daysBefore) {
        this(version,customerName,tourTitle,departureId,departureAt,passengerCount,amountVnd,transactionRef,paidAt,holdUntil,daysBefore,null);
    }
    public static MailPayload test() {
        return new MailPayload(1, "Danh", "Kiểm tra hệ thống email", null, null,
                0, null, null, null, null, null);
    }
}
