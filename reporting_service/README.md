# reporting_service

Reporting read model cho admin dashboard (Phase 2 slice #1).

- Port: `8092`, context path `/reporting`
- Database: `sgu_reporting` (MySQL `3318` trong `docker-compose.microservice.yml`)
- Sở hữu: `operation_audit`, `ops_booking`, `ops_payment`, `ops_invoice`, `ops_tour`, `ops_user`, `ops_refund`
- Không query database của service khác.

## Cấu hình

| Biến | Mặc định | Ghi chú |
|---|---|---|
| `DB_URL` | `jdbc:mysql://localhost:3318/sgu_reporting` | DB riêng của reporting |
| `DB_PASSWORD` | — | Password user `sgu_reporting` |
| `REPORTING_INTERNAL_KEY` | — | Bắt buộc, tối thiểu 32 ký tự, gửi qua `X-Internal-Reporting-Key` |
| `DB_POOL_MAX` | `5` | Hikari pool |
| `DB_POOL_MIN` | `1` | Hikari idle |

`booking_service` chỉ gọi reporting khi:

```properties
REPORTING_ENABLED=true
REPORTING_BASE_URL=http://localhost:8092/reporting
REPORTING_INTERNAL_KEY=<cùng key với reporting_service>
```

Nếu reporting lỗi hoặc chưa cấu hình key, booking dùng lại query legacy để không gián đoạn dashboard.

## Internal API

- `POST /internal/reports/audit`
- `GET /internal/reports/audit?page=0`
- `GET /internal/reports/operations?from=...&through=...`
- `POST /internal/reports/projections/{bookings,payments,invoices,tours,users,refunds}`

Hợp đồng chi tiết: `docs/INTERNAL_API_CONTRACTS.md` mục 7.

## Chạy và test

```powershell
mvn test
mvn spring-boot:run
```

Test dùng H2 (`MODE=MySQL`), không cần MySQL. Trước khi test với DB thật, chạy lại
`data_seed/microservices/sgu_reporting.sql` trên volume mới; schema đã thêm `ops_refund` và
`refund_amount/refunded_at` cho `ops_booking`, `ops_payment`.
