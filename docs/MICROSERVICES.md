# Microservice — SGU Tour Backend

> Tài liệu này là nguồn tham chiếu chính cho kiến trúc microservice của dự án.  
> Thay thế và gộp nội dung từ: `roadmap_microservice.md`, `review-plan.md`, phần kiến trúc của `PROJECT.md`, `INTERNAL_API_CONTRACTS.md` và cấu hình Compose liên quan từ `DOCKER.md`.

## 1. Mục tiêu

Hệ thống đặt tour du lịch SGU Tour chia thành các ứng dụng Spring Boot độc lập, giao tiếp qua REST (Spring Cloud Gateway + OpenFeign) và sự kiện (outbox/inbox, saga). 

Hiện tại các service nghiệp vụ dùng chung database `dbtour_v2` (shared-database). Tài liệu định nghĩa đích vận hành: 9 database vật lý độc lập (database-per-service), không cross-database SQL, không transaction phân tán, sự nhất quán giữa các service do internal API + event + saga đảm bảo.

## 2. Danh mục service

| Module | Cổng | Context | Cấu trúc Maven | Vai trò chính |
|---|---:|---|---|---|
| `api_gateway` | 8888 | không có | `ApiGatewayApplication` | Định tuyến `/api/v1`, xác thực, phân quyền, giới hạn yêu cầu |
| `auth_service` | 8080 (Socket.IO 8099) | `/auth` | `AuthServiceApplication` | Tài khoản, JWT, refresh/revoke, Google, role/permission, đánh giá |
| `booking_service` | 8081 | `/booking` | `BookingServiceApplication` | Đặt tour, giữ/trả chỗ, dời lịch, email outbox |
| `payment_service` | 8083 | `/payment` | `PaymentServiceApplication` | VNPay/MoMo sandbox, callback, đối soát |
| `pricing_service` | 8084 | `/pricing` | `PricingServiceApplication` | Bảng giá nhóm tuổi, phụ thu |
| `tour_service` | 8086 | `/tour` | `TourServiceApplication` | Tour, lịch trình, địa điểm, xe, lịch khởi hành, sức chứa |
| `file_service` | 8087 | `/file` | `FileServiceApplication` | Upload ảnh Cloudinary, gallery/avatar, throttling |
| `invoice_service` | 8090 | `/invoice` | `InvoiceServiceApplication` | Hóa đơn theo booking |
| `search_service` | 8089 | `/search` | `SearchServiceApplication` | Tìm kiếm Elasticsearch, cache Redis |
| `ai_service` | 8091 | `/ai` | `AiServiceApplication` | Tư vấn tour qua Spring AI/Groq |

Mỗi module là project Maven riêng (`pom.xml` + `Dockerfile`), chạy tại thư mục module, không phải thư mục gốc.

## 3. Kiến trúc đích (database-per-service)

| Database | Service sở hữu | Bảng chính |
|---|---|---|
| `sgu_auth` | `auth_service` | users, roles, permissions, permission_role, invalidated_token, user_account_event, reviews |
| `sgu_tour` | `tour_service` | tour, itinerary, location, vehicles, tour_departure, tour_image_ids, departure_meeting_point, departure_hold |
| `sgu_pricing` | `pricing_service` | prices |
| `sgu_booking` | `booking_service` | booking, customer, booking_reservation, booking_price_snapshot, booking_change, booking_change_event, departure_change |
| `sgu_payment` | `payment_service` | payment, vnpay_transaction, momo_transaction, payment_reconciliation, payment_reconciliation_event |
| `sgu_invoice` | `invoice_service` | invoice |
| `sgu_file` | `file_service` | file_mgmt, avatar_media_lock, tour_media_lock |
| `sgu_notification` | `notification_service` | mail_outbox, mail_template, mail_delivery_attempt, mail_suppression |
| `sgu_reporting` | `reporting_service` | operation_audit, ops_booking, ops_payment, ops_refund, ops_invoice, ops_tour, ops_user |

### Nguyên tắc không thương lượng

1. Mỗi database có user/grant riêng; không có cross-database FK.
2. Không có `JOIN` hay truy vấn bảng của service khác từ code.
3. Không dùng distributed transaction/2PC; sự nhất quán bằng internal API + outbox + inbox + saga.
4. Idempotency bắt buộc cho callback thanh toán, sự kiện tiêu thụ và retry HTTP.
5. Reporting chỉ đọc read model `ops_*` của chính mình, không query DB nguồn.
6. Search/AI không sở hữu DB nghiệp vụ; chỉ gọi API hoặc dùng snapshot.
7. Không ghi đè dữ liệu lịch sử (booking, payment, invoice, audit).
8. Thay đổi theo vertical slice: mỗi lát cắt chạy được, có test, có rollback.
9. Không đưa secret vào repo (`.local/`, `.env`, credential thật không commit).
10. Không big-bang cutover; giữ `dbtour_v2` làm nguồn đối chiếu đến hết giai đoạn cuối.

## 4. Sự kiện và envelope

Mọi sự kiện dùng envelope chung:

```json
{
  "eventId": "uuid",
  "eventType": "booking.created.v1",
  "occurredAt": "2026-11-20T10:00:00+07:00",
  "producer": "booking_service",
  "aggregateType": "booking",
  "aggregateId": "123",
  "aggregateVersion": 1,
  "correlationId": "request-id",
  "causationId": "event-id-or-command-id",
  "payload": {}
}
```

Quy tắc: `eventId` duy nhất toàn hệ thống; consumer ghi inbox trước khi xử lý (idempotent); payload chỉ chứa dữ liệu consumer cần; tên sự kiện có version; không chứa secret/token/thông tin thanh toán nhạy cảm.

Sự kiện ban đầu: `auth.user.registered`, `booking.created/confirmed/cancelled/expired`, `tour.departure.changed/cancelled`, `payment.settled/failed/refunded`, `invoice.issued/paid`, `file.deleted`, `booking.change.requested/approved`.

## 5. Internal API (gateway `/internal/{service}/...`)

Quy tắc chung: base path `/internal/{service}/...`, auth bằng JWT shared `JWT_SIGNER_KEY` hoặc header `X-Internal-Key`, timeout 5s connect/10s read, retry 3 lần cho GET idempotent, lỗi theo RFC 7807.

Tour (ví dụ): `POST /internal/tour/departures/{id}/holds` (giữ ghế), `DELETE /internal/tour/departures/{id}/holds/{holdId}` (trả ghế), `GET /internal/tour/departures/{id}` (thông tin lịch), `GET /internal/tour/departures/{id}/capacity` (sức chứa), `GET /internal/tour/tours/{id}/price` (giá), `POST /internal/tour/tours/{id}/price/quote` (tính tiền danh sách hành khách).

Chi tiết đầy đủ và các service khác xem `docs/INTERNAL_API_CONTRACTS.md`.

## 6. Lộ trình triển khai (không timeline)

### Phase 0 — Baseline và hợp đồng
- Chốt ownership, event catalog, internal API catalog.
- Cập nhật `docs/PROJECT.md`, `docs/DOCKER.md`, `data_seed/README.md` theo đích mới.
- Thêm script kiểm tra isolation (không `REFERENCES` chéo DB, không `dbtour_v2` trong code mới).
- Gate: tài liệu được review; chưa đổi runtime.

### Phase 1 — Hạ tầng (đã nghiệm thu)
- Thêm RabbitMQ và 9 MySQL độc lập vào `docker-compose.microservice.yml`.
- Tạo `data_seed/microservices/*.sql` cho từng DB (9 file).
- Thêm outbox/inbox schema (`<svc>_outbox`, `<svc>_inbox`).
- Thêm `docs/INTERNAL_API_CONTRACTS.md`.
- Gate: Compose config PASS; từng DB khởi động độc lập; user/grant riêng; không cross-DB FK.

### Phase 2 — Refactor vertical slices (trên shared DB)
Thứ tự đề xuất: Reporting (mới) → Notification (mới) → File → Pricing → Tour → Auth → Booking → Payment → Invoice → Search/AI.
Mỗi slice phải có internal API/event rõ ràng, test đơn vị + tích hợp với DB của service, không làm tăng cross-domain SQL, có feature flag và rollback.

### Phase 3 — Tách database vật lý
- Tạo 9 MySQL instance/container, user riêng, volume riêng.
- Chạy migration `data_seed/microservices/*.sql`.
- Chuyển `DB_URL` từng service sang DB riêng.
- Migrate dữ liệu theo thứ tự: auth → tour → pricing → booking → payment → invoice → file → notification → reporting.
- Gate: service khởi động với chỉ DB của mình; cross-DB query bị từ chối bởi grant.

### Phase 4 — Saga và đồng bộ
- Outbox publisher + inbox consumer.
- Saga booking hold/expiry, payment/invoice, reschedule/refund.
- Retry backoff, DLQ, replay có kiểm soát.
- Reporting projection + audit timeline.
- Gate: duplicate event không tạo bản ghi trùng; consumer chết không mất event.

### Phase 5 — Cleanup và acceptance
- Xóa `dbtour_v2` khỏi runtime sau khi đối chiếu.
- Xóa code/table/column legacy, mail outbox cũ, cross-domain reader.
- Cập nhật README, Compose, CI, script demo.
- Chạy E2E trên môi trường 16 GB.

## 7. Checklist chấp nhận

- [ ] 9 database vật lý độc lập, volume riêng.
- [ ] Mỗi service chỉ có grant trên DB của mình.
- [ ] Không còn cross-database query/FK/transaction.
- [ ] Không còn service đọc bảng ngoài ownership.
- [ ] Outbox/inbox hoạt động; sự kiện trùng không gây duplicate.
- [ ] Saga booking/payment/invoice/reschedule có compensation.
- [ ] Reporting không query DB nguồn.
- [ ] Notification sở hữu mail outbox/template/delivery.
- [ ] File service sở hữu toàn bộ metadata ảnh.
- [ ] Search/AI chỉ dùng API/snapshot.
- [ ] Local test tuần tự đạt; E2E 16 GB đạt.
- [ ] Docs, Compose, CI, script demo khớp topology mới.
- [ ] Không có secret trong repo hoặc file mẫu.

## 8. Kiểm thử

Backend không có lệnh `mvn test` tổng tại thư mục gốc; chạy từng module:

```bash
mvn -B --no-transfer-progress -f auth_service/pom.xml test
```

Chạy toàn bộ tuần tự (Linux/WSL/Git Bash):

```bash
modules=(payment_service booking_service tour_service file_service auth_service pricing_service invoice_service api_gateway search_service ai_service)
for module in "${modules[@]}"; do
  mvn -B --no-transfer-progress -f "$module/pom.xml" test || exit 1
done
```

Frontend:

```bash
npm --prefix ../Frontend_TourDuLich ci
npm --prefix ../Frontend_TourDuLich run lint
npm --prefix ../Frontend_TourDuLich run build
```

Docker Compose (tất cả profile search/ai/mail):

```bash
docker compose --profile search --profile ai --profile mail --parallel 1 build
docker compose --profile search --profile ai --profile mail up -d
docker compose --profile search --profile ai --profile mail ps
```

## 9. Tài liệu liên quan

- [Backend README](../README.md) — khởi chạy, cấu hình, kiểm thử, bảo mật.
- [Kiến trúc và cấu hình hệ thống](PROJECT.md) — chi tiết biến môi trường, luồng dữ liệu, quy ước demo.
- [Docker và dữ liệu](DOCKER.md) — vận hành Compose, volume, kết hợp IntelliJ/Maven.
- [Schema và dữ liệu mẫu](../data_seed/README.md) — `database_all.sql`, `004_travel_operations.sql`, các file seed microservice.
- [Internal API Contracts](INTERNAL_API_CONTRACTS.md) — định nghĩa chi tiết request/response từng endpoint nội bộ.
- [Nguồn dữ liệu tour](DATA_SOURCES.md) — quy ước và bản quyền dữ liệu demo.
- [AI](ai_service/README.md) · [Email/n8n](mail/README.md).
