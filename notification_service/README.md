# notification_service

Notification worker và mail outbox riêng (Phase 2 slice #2).

- Port: `8093`, context path `/notification`
- Database: `sgu_notification` (MySQL `3317` trong `docker-compose.microservice.yml`)
- Sở hữu: `mail_outbox`, `mail_template`, `mail_delivery_attempt`, `mail_suppression`
- Không đọc DB booking/payment; producer gửi mail qua internal API.

## Cấu hình

| Biến | Mặc định | Ghi chú |
|---|---|---|
| `DB_URL` | `jdbc:mysql://localhost:3317/sgu_notification` | DB riêng của notification |
| `DB_PASSWORD` | — | Password user `sgu_notification` |
| `NOTIFICATION_INTERNAL_KEY` | — | Bắt buộc, tối thiểu 32 ký tự, header `X-Internal-Notification-Key` |
| `MAIL_ENABLED` | `false` | Bật worker gửi SMTP |
| `MAIL_TEST_MODE` | `true` | Chỉ gửi tới `MAIL_TEST_RECIPIENT` |
| `MAIL_TEST_RECIPIENT` | `no-reply@sgu-tour.example` | Hộp thư test |
| `MAIL_FROM` | `no-reply@sgu-tour.example` | Địa chỉ gửi |
| `MAIL_SMTP_USERNAME`, `MAIL_SMTP_PASSWORD` | — | Credential SMTP |

## Internal API

- `POST /internal/notifications/mail` — enqueue idempotent theo `eventKey`
- `GET /internal/notifications/mail/bookings/{bookingId}` — 30 delivery gần nhất
- `POST /internal/notifications/mail/bookings/{bookingId}/{id}/retry` — retry mail FAILED

Hợp đồng chi tiết: `docs/INTERNAL_API_CONTRACTS.md`.

## Chạy và test

```powershell
mvn test
mvn spring-boot:run
```

Test dùng H2 (`MODE=MySQL`), không cần SMTP/MySQL. Worker chỉ chạy khi `MAIL_ENABLED=true`.
