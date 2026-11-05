package com.example.payment_service.dto.response;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.util.Map;

/** Giá phải trả do server tính, để giao diện không tự tính lại từ bảng giá. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class BookingQuoteResponse {

    String amount;

    Map<String, String> unitPrices;
}
