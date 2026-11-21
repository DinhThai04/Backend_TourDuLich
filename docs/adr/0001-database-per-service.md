# ADR 0001 — Database per service

- Trạng thái: Accepted
- Ngày: 2026-11-20
- Người quyết định: Nhóm SGU Tour

## Bối cảnh

Các service hiện dùng chung `dbtour_v2`. Cách này đơn giản cho local/demo nhưng
không đáp ứng yêu cầu microservice: service có thể đọc/ghi bảng của nhau, transaction
trải nhiều domain và không thể scale/tách lỗi độc lập.

## Quyết định

Chuyển sang 9 database vật lý độc lập:

- `sgu_auth`
- `sgu_tour`
- `sgu_pricing`
- `sgu_booking`
- `sgu_payment`
- `sgu_invoice`
- `sgu_file`
- `sgu_notification`
- `sgu_reporting`

Mỗi database có:

- Một service sở hữu schema.
- User/grant riêng, chỉ service đó truy cập.
- Migration riêng.
- Backup/restore riêng.

## Hệ quả

Tích cực:

- Ranh giới domain rõ ràng.
- Có thể scale từng service.
- Lỗi một DB không kéo toàn bộ hệ thống.
- Đáp ứng rubric microservice/database-per-service.

Tiêu cực:

- Không còn JOIN/transaction xuyên domain.
- Cần internal API, event, outbox/inbox, saga.
- Cần reporting read model.
- Local 8 GB phải chạy tuần tự.

## Không chấp nhận

- Một MySQL chứa 9 schema.
- Cross-database FK.
- Grant chung cho nhiều service.
- Reporting query trực tiếp DB nguồn.
- Distributed transaction/2PC.
