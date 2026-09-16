package com.example.invoice_service.dto.request;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class MoMoRequest {
    private String partnerCode;       // Mã đối tác (ví dụ: MOMOXXXX2021)
    private String partnerName;       // Tên đối tác hiển thị
    private String storeId;           // ID cửa hàng (nếu có)
    private String requestId;         // Mã request duy nhất (UUID)
    private long amount;            // Số tiền giao dịch
    private String orderId;           // Mã đơn hàng duy nhất
    private String orderInfo;         // Thông tin mô tả đơn hàng
    private String redirectUrl;       // URL trả về sau thanh toán
    private String ipnUrl;            // URL callback IPN
    private String requestType;       // Loại yêu cầu (ví dụ: "captureWallet")
    private String extraData;         // Dữ liệu bổ sung (base64 hoặc trống)
    private String signature;         // Chữ ký hash HMAC SHA256
    private String lang;              // Ngôn ngữ hiển thị ("vi" hoặc "en")
}