# Phase 0 — Baseline & Hợp đồng Microservice

> **Trạng thái**: Draft review
> **Ngày**: 2026-11-20
> **Người thực hiện**: Nhóm SGU Tour
> **Mục tiêu**: Chốt ownership, event catalog, internal API catalog trước khi viết code Phase 1.

---

## 1. Database Ownership (chốt bảng)

| Database | Service sở hữu | Bảng (từ `database_all.sql`) | Ghi chú |
|---|---|---|---|
| `sgu_auth` | `auth_service` | `users`, `roles`, `permissions`, `permission_role`, `invalidated_token`, `user_account_event`, `reviews` | `reviews` do auth quản lý vì gắn user |
| `sgu_tour` | `tour_service` | `tour`, `prices`, `itinerary`, `location`, `vehicles`, `tour_departure`, `tour_image_ids`, `tour_media_lock`, `departure_hold` | `departure_hold` là bảng mới cho capacity hold |
| `sgu_pricing` | `pricing_service` | `prices` | Chỉ bảng giá, không có bảng khác |
| `sgu_booking` | `booking_service` | `booking`, `customer`, `booking_reservation`, `booking_price_snapshot`, `booking_change`, `booking_change_event`, `departure_change` | Tách `payment`, `invoice` ra DB riêng |
| `sgu_payment` | `payment_service` | `payment`, `vnpay_transaction`, `momo_transaction`, `payment_reconciliation`, `payment_reconciliation_event` | |
| `sgu_invoice` | `invoice_service` | `invoice` | |
| `sgu_file` | `file_service` | `file_mgmt`, `avatar_media_lock`, `tour_media_lock` | |
| `sgu_notification` | `notification_service` | `mail_outbox`, `mail_template`, `mail_delivery_attempt`, `mail_suppression` | Bảng mới, tách từ booking/payment |
| `sgu_reporting` | `reporting_service` | `operation_audit`, `ops_booking`, `ops_payment`, `ops_invoice`, `ops_tour`, `ops_user` | Read model, không phải nguồn sự thật |

**Quy tắc**: Mỗi DB chỉ service sở hữu được grant `ALL`. Các service khác **không** được grant `SELECT` trên DB không thuộc sở hữu.

---

## 2. Event Catalog (chốt version v1)

| Event | Producer | Consumer | Payload tối thiểu |
|---|---|---|---|
| `auth.user.registered.v1` | auth | notification, reporting | `userId`, `email`, `role` |
| `auth.user.locked.v1` | auth | booking, reporting | `userId` |
| `auth.user.archived.v1` | auth | booking, file, reporting | `userId` |
| `tour.departure.changed.v1` | tour | booking, search, reporting | `departureId`, `tourId`, `old/new departureDate`, `old/new availableSeats`, `old/new operationalStatus` |
| `tour.departure.cancelled.v1` | tour | booking, payment, notification | `departureId`, `tourId`, `reason` |
| `tour.departure.hold.requested.v1` | booking | tour | `holdId`, `departureId`, `seats`, `ttlSeconds`, `bookingId` |
| `tour.departure.hold.released.v1` | booking | tour | `holdId`, `departureId`, `seats`, `reason` |
| `booking.created.v1` | booking | notification, reporting | `bookingId`, `accountId`, `departureId`, `seats`, `amount`, `status` |
| `booking.confirmed.v1` | booking | payment, notification, reporting | `bookingId`, `accountId`, `departureId`, `amount` |
| `booking.cancelled.v1` | booking | payment, notification, reporting | `bookingId`, `reason` |
| `booking.expired.v1` | booking | tour, payment, notification | `bookingId`, `departureId` |
| `booking.change.requested.v1` | booking | notification, reporting | `changeId`, `bookingId`, `type`, `sourceDep`, `targetDep`, `reason` |
| `booking.change.approved.v1` | booking | payment, tour, notification | `changeId`, `bookingId`, `targetDep`, `refundAmount` |
| `payment.settled.v1` | payment | booking, invoice, reporting | `paymentId`, `bookingId`, `amount`, `provider`, `txnRef`, `paidAt` |
| `payment.failed.v1` | payment | booking, notification, reporting | `paymentId`, `bookingId`, `provider`, `reason` |
| `payment.refunded.v1` | payment | booking, invoice, notification, reporting | `paymentId`, `bookingId`, `amount`, `refundId` |
| `invoice.issued.v1` | invoice | notification, reporting | `invoiceId`, `bookingId`, `amount` |
| `invoice.paid.v1` | invoice | reporting | `invoiceId`, `bookingId` |
| `file.deleted.v1` | file | tour, auth, reporting | `fileId`, `ownerType`, `ownerId`, `url` |
| `report.audit.recorded.v1` | auth/tour/pricing/booking/payment/invoice | reporting | `actorId`, `action`, `entityType`, `entityId`, `detail` |

**Envelope chuẩn**:
```json
{
  "eventId": "uuid-v4",
  "eventType": "booking.created.v1",
  "occurredAt": "2026-11-20T10:00:00+07:00",
  "producer": "booking_service",
  "aggregateType": "booking",
  "aggregateId": "123",
  "aggregateVersion": 1,
  "correlationId": "request-uuid",
  "causationId": "command-uuid",
  "payload": {}
}
```

---

## 3. Internal API Catalog (contract giữa service)

Mỗi service expose **internal endpoints** (chỉ gọi qua gateway nội bộ, không public).

### 3.1 Tour Service → `/internal/tour/...`

| Endpoint | Method | Mục đích | Consumer |
|---|---|---|---|
| `/internal/departures/{id}/holds` | POST | Giữ ghế: body `{seats, ttlSeconds, bookingId}` | booking |
| `/internal/departures/{id}/holds/{holdId}` | DELETE | Trả ghế | booking |
| `/internal/departures/{id}` | GET | Lấy thông tin lịch (capacity, status, dates) | booking, payment, invoice |
| `/internal/departures/{id}/capacity` | GET | Lấy availableSeats, capacity, holds | booking |
| `/internal/tours/{id}/price` | GET | Lấy giá tour (adult/child/toddler/infant) | booking, payment |
| `/internal/departures/bulk` | POST | Lấy nhiều lịch một lần (cho search/admin) | search, booking |

### 3.2 Pricing Service → `/internal/pricing/...`

| Endpoint | Method | Mục đích | Consumer |
|---|---|---|---|
| `/internal/tours/{id}/price` | GET | Lấy `TourPriceDTO` đầy đủ | booking, payment |
| `/internal/tours/{id}/price/quote` | POST | Tính tiền cho danh sách khách | booking |

### 3.3 Booking Service → `/internal/booking/...`

| Endpoint | Method | Mục đích | Consumer |
|---|---|---|---|
| `/internal/bookings/{id}` | GET | Lấy booking đầy đủ (cho payment/invoice) | payment, invoice |
| `/internal/bookings/{id}/summary` | GET | Tóm tắt: status, amount, departureId, accountId | payment, invoice, notification |
| `/internal/bookings/{id}/customers` | GET | Danh sách khách (loại, giá) | payment, invoice |
| `/internal/reservations/{bookingId}` | GET | Trạng thái hold/checkout | payment |

### 3.4 Payment Service → `/internal/payment/...`

| Endpoint | Method | Mục đích | Consumer |
|---|---|---|---|
| `/internal/payments/by-booking/{bookingId}` | GET | Lấy payment transaction cho booking | booking, invoice |

### 3.5 File Service → `/internal/file/...`

| Endpoint | Method | Mục đích | Consumer |
|---|---|---|---|
| `/internal/files/lock-avatar/{ownerId}` | POST | Khóa avatar trước upload | auth |
| `/internal/files/{fileId}/verify` | GET | Kiểm tra file tồn tại và owner | auth, tour |
| `/internal/files/cleanup-refs/{ownerId}` | DELETE | Dọn tham chiếu khi xóa avatar/tour | auth, tour |

### 3.6 Auth Service → `/internal/auth/...`

| Endpoint | Method | Mục đích | Consumer |
|---|---|---|---|
| `/internal/users/{id}/exists` | GET | Kiểm tra user tồn tại | booking, file |
| `/internal/users/{id}/status` | GET | Lấy status user (ACTIVE/LOCKED/ARCHIVED) | booking |

---

## 4. Cross-Database Blocker (từ `roadmap_microservice.md` §3.2)

| Blocker | Vị trí code | Hướng giải quyết |
|---|---|---|
| Booking đọc/khóa `tour_departure` trực tiếp | `BookingLifecycleService.java:102`, `ReservationExpiryService.java`, `ChangeLedger.java` | Tour cung cấp internal API hold/release; booking chỉ gọi API |
| Booking đọc `prices`, `tour`, `users` | `BookingLifecycleService.java`, `BookingChangeService.java`, `ReminderStateReader.java` | Pricing/tour/auth cung cấp internal API |
| Payment đọc `prices`, `tour`, `booking` | `VnPayOrderService.java:189`, `MoMoService.java` | Payment chỉ giữ dữ liệu thanh toán; quote lấy từ pricing/booking API |
| Invoice/payment đọc chéo settlement | `VnPayOrderService.java:370`, `MoMoOrderService.java`, `ReconciliationService.java` | Saga payment→invoice; mỗi bên ghi DB của mình |
| Auth đọc booking/payment/invoice/file | `AccountLifecycleService.java:58`, `AvatarProfileWriter.java` | Gọi internal API/event; file service sở hữu `file_mgmt` |
| Tour đọc booking/payment/invoice để capacity | `DepartureCapacityReader.java:42` | Booking/payment/invoice phát event; tour giữ projection `departure_hold` |
| Booking join 4 bảng cho AI | `BookingAdviceService.java:45` | Booking API tổng hợp qua internal API |
| OperationsInsights join 6 bảng | `OperationsInsights.java` | Chuyển sang `reporting_service` + `ops_*` read model |
| Mail outbox rải rác | `mail_outbox` trong booking/payment | `notification_service` sở hữu outbox/template/delivery |
| Tour xóa ảnh bằng SQL `file_mgmt` | `TourService.java:412` | Gọi file service internal API |
| Auth/tour/booking xóa/kiểm tra `file_mgmt` | auth/tour/booking services | File service cung cấp API kiểm tra/xóa/cleanup |
| Search đọc snapshot tour DB | `search_service` | Giữ snapshot/event; không đọc DB tour trực tiếp |
| AI đọc tour/booking qua HTTP | `ai_service` | Giữ API-only; thêm contract test/timeout/fallback |

---

## 5. Schema Migration Plan (từ `database_all.sql`)

### 5.1 Bảng mới cần thêm mỗi DB

Mỗi service DB thêm:
```sql
-- Outbox (ghi cùng transaction nghiệp vụ)
CREATE TABLE <service>_outbox (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id VARCHAR(36) NOT NULL UNIQUE,
  event_type VARCHAR(120) NOT NULL,
  aggregate_type VARCHAR(80) NOT NULL,
  aggregate_id VARCHAR(80) NOT NULL,
  aggregate_version INT NOT NULL,
  correlation_id VARCHAR(80),
  causation_id VARCHAR(80),
  payload JSON NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  attempts INT NOT NULL DEFAULT 0,
  available_at DATETIME(6) NOT NULL,
  published_at DATETIME(6),
  last_error VARCHAR(1000),
  created_at DATETIME(6) NOT NULL,
  KEY idx_outbox_status_available (status, available_at)
);

-- Inbox (ghi trước khi xử lý event)
CREATE TABLE <service>_inbox (
  consumer VARCHAR(120) NOT NULL,
  event_id VARCHAR(36) NOT NULL,
  event_type VARCHAR(120) NOT NULL,
  received_at DATETIME(6) NOT NULL,
  processed_at DATETIME(6),
  PRIMARY KEY (consumer, event_id),
  KEY idx_inbox_unprocessed (processed_at)
);
```

### 5.2 Bảng infrastructure mới

| Database | Bảng mới | Mục đích |
|---|---|---|
| `sgu_tour` | `departure_hold` | Ghế đang giữ theo booking: `hold_id`, `departure_id`, `booking_id`, `seats`, `status` (HELD/RELEASED/EXPIRED), `expires_at`, `version` |
| `sgu_file` | `file_reference` | Tham chiếu file từ tour/avatar/booking: `file_id`, `owner_type` (TOUR/AVATAR/BOOKING), `owner_id`, `created_at` |
| `sgu_booking` | `booking_saga` | Trạng thái saga booking hold/reschedule | 
| `sgu_payment` | `payment_saga` | Trạng thái saga payment/refund |
| `sgu_reporting` | `ops_*` | Read model cho admin dashboard |

---

## 6. Gate Checklist Phase 0

- [ ] Ownership table chốt, không còn tranh chấp bảng
- [ ] Event catalog review, version v1 đồng ý
- [ ] Internal API contract review, đủ cho Phase 2 slices
- [ ] Cross-database blocker list khớp code hiện tại
- [ ] ADR 0001/0002/0003 đã accepted
- [ ] `docs/PROJECT.md`, `docs/DOCKER.md`, `data_seed/README.md` cập nhật theo đích mới
- [ ] Script kiểm tra isolation: `grep -r "dbtour_v2" --include="*.java" src/main` → 0 match (trừ comment)

---

## 7. Kết luận Phase 0

Tài liệu này là **baseline** cho toàn bộ giai đoạn chuyển microservice. Mọi thay đổi lớn (ownership, event, API contract) phải cập nhật lại file này trước khi triển khai Phase 1+.

**Next**: Phase 1 — Tạo `docker-compose.microservice.yml` với 9 MySQL + RabbitMQ, schema từng DB từ `data_seed/microservices/*.sql`.