# ADR 0002 — Transactional outbox và inbox idempotency

- Trạng thái: Accepted
- Ngày: 2026-11-20
- Người quyết định: Nhóm SGU Tour

## Bối cảnh

Khi tách database, các thao tác như thanh toán → hóa đơn → email không thể nằm trong
một transaction. Nếu ghi DB rồi publish event ngay, có thể mất event khi publish lỗi.
Nếu publish trước rồi ghi DB, consumer có thể thấy event cho dữ liệu chưa commit.

## Quyết định

Dùng transactional outbox ở producer và inbox idempotency ở consumer.

### Outbox

Mỗi service có bảng `<service>_outbox`:

```sql
CREATE TABLE booking_outbox (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id VARCHAR(36) NOT NULL UNIQUE,
  event_type VARCHAR(120) NOT NULL,
  aggregate_type VARCHAR(80) NOT NULL,
  aggregate_id VARCHAR(80) NOT NULL,
  aggregate_version INT NOT NULL,
  correlation_id VARCHAR(80),
  payload JSON NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  attempts INT NOT NULL DEFAULT 0,
  available_at DATETIME(6) NOT NULL,
  published_at DATETIME(6),
  last_error VARCHAR(1000),
  created_at DATETIME(6) NOT NULL
);
```

- Event được ghi cùng transaction nghiệp vụ.
- Publisher riêng đọc `PENDING`, publish RabbitMQ, đánh dấu `PUBLISHED`.
- Retry có backoff; quá số lần → `FAILED` + cảnh báo.
- Không xóa event ngay; archive sau khi đối chiếu.

### Inbox

Mỗi consumer có bảng `<service>_inbox`:

```sql
CREATE TABLE booking_inbox (
  consumer VARCHAR(120) NOT NULL,
  event_id VARCHAR(36) NOT NULL,
  event_type VARCHAR(120) NOT NULL,
  received_at DATETIME(6) NOT NULL,
  processed_at DATETIME(6),
  PRIMARY KEY (consumer, event_id)
);
```

- Ghi inbox trước khi xử lý.
- Event trùng bị bỏ qua.
- Xử lý lỗi thì để inbox ở trạng thái chưa `processed_at` và retry.
- Consumer phải idempotent cả khi event được gửi lại.

## Hệ quả

- Có ít nhất một lần giao event; consumer phải idempotent.
- Cần bảng outbox/inbox cho từng service.
- Cần publisher/consumer library hoặc cấu hình chung.
- Cần DLQ, replay và giám sát backlog.

## Không chấp nhận

- Publish event trực tiếp trong transaction DB mà không có outbox.
- Consumer giả định event chỉ đến một lần.
- Dùng event làm nguồn sự thật duy nhất cho dữ liệu tài chính.
