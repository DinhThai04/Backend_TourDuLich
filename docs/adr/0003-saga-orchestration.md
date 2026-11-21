# ADR 0003 — Saga cho luồng nhiều service

- Trạng thái: Accepted
- Ngày: 2026-11-20
- Người quyết định: Nhóm SGU Tour

## Bối cảnh

Các luồng đặt tour, thanh toán, hóa đơn, đổi lịch và hoàn tiền trải nhiều service.
Không thể dùng một transaction ACID sau khi tách DB.

## Quyết định

Dùng saga orchestration cho các luồng có bước bù trừ:

- Booking hold/expiry: booking điều phối, tour giữ/trả ghế.
- Payment → invoice: payment phát event, invoice tạo hóa đơn; lỗi thì retry/DLQ.
- Reschedule/refund: booking điều phối, tour giữ ghế đích, payment hoàn tiền.
- Account delete: auth điều phối, file cleanup, notification.

Mỗi saga có bảng trạng thái trong DB của orchestrator:

```sql
CREATE TABLE booking_saga (
  id VARCHAR(36) PRIMARY KEY,
  saga_type VARCHAR(80) NOT NULL,
  aggregate_id VARCHAR(80) NOT NULL,
  status VARCHAR(30) NOT NULL,
  current_step VARCHAR(120) NOT NULL,
  payload JSON NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  last_error VARCHAR(1000),
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL
);
```

Quy tắc:

- Mỗi bước idempotent.
- Mỗi bước có timeout và compensation.
- Không gọi HTTP ra service khác trong transaction DB.
- Trạng thái `FAILED`/`COMPENSATING` cần thao tác admin có audit.
- Event/command đều có `correlationId` để trace.

## Hệ quả

- Phức tạp hơn transaction đơn giản.
- Cần dashboard/alert cho saga kẹt.
- Cần test failure injection.
- Đổi lại, hệ thống chịu lỗi và nhất quán cuối.
