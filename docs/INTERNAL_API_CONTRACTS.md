# Internal API Contract Specification

> **File**: `docs/INTERNAL_API_CONTRACTS.md`
> **Mục đích**: Định nghĩa chi tiết request/response cho các internal endpoint giữa service.
> **Sử dụng**: Phase 2 implement vertical slices.

---

## Quy tắc chung

- **Base path**: `/internal/{service}/...` (gateway route `/internal` → service tương ứng)
- **Auth**: Internal service-to-service JWT (shared `JWT_SIGNER_KEY`) hoặc API key header `X-Internal-Key`
- **Timeout**: 5s connect, 10s read (configurable per endpoint)
- **Retry**: 3 lần với exponential backoff (100ms, 200ms, 400ms) cho idempotent GET
- **Error format**: RFC 7807 ProblemDetail
- **Versioning**: URL không version; breaking change → endpoint mới v2

---

## 1. Tour Service Internal API

### 1.1 POST `/internal/tour/departures/{id}/holds`

**Mô tả**: Giữ ghế cho booking

**Request**:
```json
{
  "seats": 2,
  "ttlSeconds": 900,
  "bookingId": 123,
  "clientKey": "uuid-v4"
}
```

**Response 201**:
```json
{
  "holdId": "uuid-v4",
  "departureId": 456,
  "seats": 2,
  "status": "HELD",
  "expiresAt": "2026-11-20T10:15:00+07:00"
}
```

**Error**:
- `409 CONFLICT` — Hết ghế hoặc `operational_status != OPEN`
- `404 NOT_FOUND` — Departure không tồn tại
- `400 BAD_REQUEST` — Seats ≤ 0, TTL ≤ 0

---

### 1.2 DELETE `/internal/tour/departures/{id}/holds/{holdId}`

**Mô tả**: Trả ghế

**Response 204**: No content

**Error**:
- `404 NOT_FOUND` — Hold không tồn tại hoặc đã RELEASED/EXPIRED
- `409 CONFLICT` — Hold không thuộc booking này (nếu cung cấp bookingId)

---

### 1.3 GET `/internal/tour/departures/{id}`

**Mô tả**: Lấy thông tin lịch đầy đủ

**Response 200**:
```json
{
  "id": 456,
  "tourId": 12,
  "tourCode": "MB-SAPA-3N2D-01",
  "departureDate": "2026-12-20T06:00:00+07:00",
  "returnDate": "2026-12-22T20:00:00+07:00",
  "capacity": 36,
  "availableSeats": 30,
  "operationalStatus": "OPEN",
  "version": 5
}
```

---

### 1.4 GET `/internal/tour/departures/{id}/capacity`

**Mô tả**: Lấy capacity snapshot cho booking hold

**Response 200**:
```json
{
  "departureId": 456,
  "capacity": 36,
  "availableSeats": 30,
  "heldSeats": 4,
  "holds": [
    { "holdId": "uuid", "bookingId": 123, "seats": 2, "expiresAt": "..." }
  ]
}
```

---

### 1.5 GET `/internal/tour/tours/{id}/price`

**Mô tả**: Lấy giá tour theo nhóm tuổi

**Response 200**:
```json
{
  "tourId": 12,
  "adultPrice": 3550000,
  "childPrice": 1775000,
  "toddlerPrice": 0,
  "infantPrice": 0,
  "singleSupplementPrice": 500000,
  "currency": "VND"
}
```

---

### 1.6 POST `/internal/tour/tours/{id}/price/quote`

**Mô tả**: Tính tiền cho danh sách khách

**Request**:
```json
{
  "customers": [
    { "bookingType": "ADULT" },
    { "bookingType": "CHILD" },
    { "bookingType": "TODDLER" }
  ]
}
```

**Response 200**:
```json
{
  "totalAmount": 5325000,
  "breakdown": [
    { "type": "ADULT", "count": 1, "unitPrice": 3550000, "subtotal": 3550000 },
    { "type": "CHILD", "count": 1, "unitPrice": 1775000, "subtotal": 1775000 },
    { "type": "TODDLER", "count": 1, "unitPrice": 0, "subtotal": 0 }
  ]
}
```

---

### 1.7 POST `/internal/tour/departures/bulk`

**Mô tả**: Lấy nhiều lịch một lần (cho search/admin)

**Request**:
```json
{
  "departureIds": [456, 457, 458]
}
```

**Response 200**:
```json
{
  "departures": [
    { "id": 456, "tourId": 12, "departureDate": "...", "availableSeats": 30, "operationalStatus": "OPEN" },
    { "id": 457, "tourId": 12, "departureDate": "...", "availableSeats": 28, "operationalStatus": "OPEN" }
  ]
}
```

---

## 2. Pricing Service Internal API

### 2.1 GET `/internal/pricing/tours/{id}/price`

**Response 200**: Giống Tour `/price` nhưng là nguồn sự thật pricing

---

### 2.2 POST `/internal/pricing/tours/{id}/price/quote`

**Response 200**: Giống Tour `/price/quote` nhưng pricing service là authority

---

## 3. Booking Service Internal API

### 3.1 GET `/internal/booking/bookings/{id}`

**Response 200**:
```json
{
  "id": 123,
  "accountId": 7,
  "tourDepartureId": 456,
  "status": "CONFIRMED",
  "seats": 2,
  "totalAmount": 7100000,
  "createdAt": "2026-11-20T10:00:00+07:00",
  "confirmedAt": "2026-11-20T10:05:00+07:00",
  "clientKey": "uuid-v4"
}
```

---

### 3.2 GET `/internal/booking/bookings/{id}/summary`

**Response 200**:
```json
{
  "bookingId": 123,
  "accountId": 7,
  "tourDepartureId": 456,
  "status": "CONFIRMED",
  "seats": 2,
  "totalAmount": 7100000,
  "currency": "VND"
}
```

---

### 3.3 GET `/internal/booking/bookings/{id}/customers`

**Response 200**:
```json
{
  "bookingId": 123,
  "customers": [
    { "id": 1, "bookingType": "ADULT", "fullName": "Nguyen Van A" },
    { "id": 2, "bookingType": "CHILD", "fullName": "Nguyen Van B" }
  ]
}
```

---

### 3.4 GET `/internal/booking/reservations/{bookingId}`

**Response 200**:
```json
{
  "bookingId": 123,
  "reservation": {
    "status": "HELD",
    "seats": 2,
    "expiresAt": "2026-11-20T10:15:00+07:00",
    "holdId": "uuid-v4"
  }
}
```

---

## 4. Payment Service Internal API

### 4.1 GET `/internal/payment/payments/by-booking/{bookingId}`

**Response 200**:
```json
{
  "bookingId": 123,
  "payments": [
    {
      "id": 10,
      "provider": "VNPAY",
      "status": "PAID",
      "amount": 7100000,
      "txnRef": "VNPAY_123_abc",
      "paidAt": "2026-11-20T10:10:00+07:00",
      "vnpayTransactionId": "uuid-v4"
    }
  ]
}
```

---

## 5. File Service Internal API

### 5.1 POST `/internal/file/files/lock-avatar/{ownerId}`

**Request**:
```json
{
  "fileId": "uuid-v4",
  "url": "https://cloudinary.com/...",
  "contentType": "image/webp",
  "size": 1024000
}
```

**Response 201**:
```json
{
  "fileId": "uuid-v4",
  "avatarOwnerId": "7",
  "url": "https://cloudinary.com/...",
  "locked": true
}
```

---

### 5.2 GET `/internal/file/files/{fileId}/verify`

**Response 200**:
```json
{
  "fileId": "uuid-v4",
  "exists": true,
  "ownerType": "AVATAR",
  "ownerId": "7",
  "url": "https://cloudinary.com/...",
  "canDelete": true
}
```

---

### 5.3 DELETE `/internal/file/files/cleanup-refs/{ownerId}`

**Mô tả**: Dọn tham chiếu khi xóa avatar/tour

**Response 204**: No content

---

## 6. Auth Service Internal API

### 6.1 GET `/internal/auth/users/{id}/exists`

**Response 200**:
```json
{
  "userId": 7,
  "exists": true,
  "status": "ACTIVE"
}
```

---

### 6.2 GET `/internal/auth/users/{id}/status`

**Response 200**:
```json
{
  "userId": 7,
  "status": "ACTIVE", -- ACTIVE, LOCKED, ARCHIVED
  "role": "KHACH_HANG"
}
```

---

## 7. Error Response Format (RFC 7807)

```json
{
  "type": "https://sgu-tour.example.com/errors/insufficient-seats",
  "title": "Không đủ ghế",
  "status": 409,
  "detail": "Chỉ còn 2 ghế trống, yêu cầu 3",
  "instance": "/internal/tour/departures/456/holds",
  "errorCode": "INSUFFICIENT_SEATS",
  "timestamp": "2026-11-20T10:00:00+07:00",
  "traceId": "uuid-v4"
}
```

**Mã lỗi chuẩn**:
| Code | HTTP | Mô tả |
|---|---|---|
| `RESOURCE_NOT_FOUND` | 404 | Entity không tồn tại |
| `CONFLICT` | 409 | Xung đột trạng thái (hết ghế, version mismatch) |
| `INSUFFICIENT_SEATS` | 409 | Không đủ ghế trống |
| `HOLD_EXPIRED` | 410 | Hold đã hết hạn |
| `INVALID_STATE_TRANSITION` | 409 | Chuyển trạng thái không hợp lệ |
| `IDEMPOTENCY_CONFLICT` | 409 | `clientKey` trùng với request đang xử lý |

---

## 8. Contract Test Requirement

Mỗi internal endpoint **bắt buộc** có contract test (Spring Cloud Contract hoặc Pact):

```java
// Ví dụ: TourDepartureHoldContractTest
@Test
void holdSeats_success() {
    given()
        .header("X-Internal-Key", internalKey)
        .body(holdRequest)
    .when()
        .post("/internal/tour/departures/456/holds")
    .then()
        .statusCode(201)
        .body("holdId", notNullValue())
        .body("status", equalTo("HELD"));
}
```

---

## 9. Versioning & Deprecation

- **v1**: URL không có version (current)
- **Breaking change**: Tạo endpoint mới `/internal/tour/v2/...`
- **Deprecation**: Header `Deprecation: true` + `Sunset: <date>` + `Link: <new-url>; rel="successor-version"`
- **Support window**: Tối thiểu 2 sprint sau khi deprecate