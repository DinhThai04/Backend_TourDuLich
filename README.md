# SGU Tour — Backend

Backend của hệ thống du lịch SGU Tour: quản lý tour, đặt chỗ, thanh toán, tài khoản và tư vấn AI. Gồm 10 ứng dụng Spring Boot độc lập; các service nghiệp vụ hiện dùng chung database **dbtour_v2**.

## Các module

| Module | Cổng / context | Chức năng |
|---|---|---|
| api_gateway | 8888 | Định tuyến `/api/v1`, xác thực và phân quyền |
| auth_service | 8080 `/auth`; Socket.IO 8099 | Tài khoản, JWT, Google, vai trò và quyền |
| booking_service | 8081 `/booking` | Đặt tour, giữ/trả chỗ, dời lịch, gửi email |
| payment_service | 8083 `/payment` | VNPay sandbox, đối soát; MoMo tuỳ chọn, chưa nối giao diện |
| pricing_service | 8084 `/pricing` | Giá theo nhóm tuổi và phụ thu |
| tour_service | 8086 `/tour` | Tour, lịch trình, địa điểm, phương tiện, lịch khởi hành |
| file_service | 8087 `/file` | Upload và quản lý ảnh qua Cloudinary |
| invoice_service | 8090 `/invoice` | Hóa đơn theo booking |
| search_service | 8089 `/search` | Tìm kiếm bằng Elasticsearch, cache Redis |
| ai_service | 8091 `/ai` | Tư vấn tour và tra cứu booking qua Spring AI/Groq |

Mỗi module là một project Maven riêng; chạy lệnh tại thư mục module, không phải thư mục gốc backend.

## Yêu cầu

- JDK 21, Maven 3.9.x và MySQL/MariaDB.
- Elasticsearch/Redis cho search; Docker nếu chạy các thành phần bằng container.
- Credential tương ứng khi dùng VNPay, Cloudinary, Google, SMTP hoặc Groq.

## Khởi chạy

1. **Database:** import [database_all.sql](data_seed/database_all.sql) một lần vào **dbtour_v2 mới/trống**. Database đã có dữ liệu thì không import lại. Hibernate không tự tạo bảng (`ddl-auto=none`).
2. **Cấu hình:** đặt biến môi trường trong IDE hoặc `.local/<module>.properties` tại thư mục backend. Cần `DB_PASSWORD` cho service có datasource và cùng `JWT_SIGNER_KEY` (ít nhất 64 byte UTF-8) cho các service xác minh JWT. Payment bắt buộc có `VNPAY_HASH_SECRET` và `VNPAY_TMN_CODE`. Xem cách cấu hình từng module trong [hướng dẫn chi tiết](docs/PROJECT.md); không commit file chứa bí mật.
3. **Chạy service:** mở terminal riêng tại từng module rồi chạy:

   ```powershell
   mvn spring-boot:run
   ```

Thứ tự: MySQL → Elasticsearch/Redis → auth, pricing, file, tour → booking, invoice, payment → search, AI → gateway. Elasticsearch/Redis có thể bật bằng `docker compose up -d elasticsearch redis` tại `search_service`.

Frontend gọi API qua `http://localhost:8888/api/v1`. Hướng dẫn Docker cho toàn hệ thống và tài khoản demo nằm trong [tổng quan hệ thống](docs/PROJECT.md).

## Build và kiểm thử

Tại từng module:

```powershell
mvn test
mvn -DskipTests package
```

Kiểm thử H2/mock không thay thế kiểm thử thanh toán, SMTP hoặc Groq thật. Không dùng database đang vận hành cho test.

## Tài liệu chi tiết

- [Kiến trúc, cấu hình và hướng dẫn chạy](docs/PROJECT.md)
- [Database và dữ liệu mẫu](data_seed/README.md)
- [Email/n8n](docs/mail/README.md)
- [AI service](ai_service/README.md) · [Chức năng AI](docs/ai/README.md)
