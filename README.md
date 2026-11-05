# SGU Tour — Backend

Các module Spring Boot của SGU Tour, cập nhật theo source ngày **01/11/2026**. Hướng dẫn tổng quan, tài khoản demo và thứ tự mở ứng dụng: [tổng quan hệ thống](docs/PROJECT.md).

## Module

| Module | Cổng / context | Phạm vi |
|---|---|---|
| api_gateway | 8888 | Route `/api/v1`, xác thực/phân quyền và giới hạn request AI |
| auth_service | 8080 `/auth`; Socket.IO 8099 | Login/register/refresh/logout, Google, users, roles, permissions, review |
| booking_service | 8081 `/booking` | Booking/hành khách, giữ/trả chỗ, dời lịch, outbox/SMTP và API nhắc nội bộ |
| payment_service | 8083 `/payment` | VNPay sandbox, lưu giao dịch và đối soát; thêm MoMo AIO v2 tuỳ chọn (thiếu credential thì báo chưa cấu hình, UI chưa nối) |
| pricing_service | 8084 `/pricing` | Giá từng nhóm tuổi và phụ thu |
| tour_service | 8086 `/tour` | Tour, lịch trình, địa điểm, phương tiện, lịch/chỗ và API catalog cho AI/search |
| file_service | 8087 `/file` | Cloudinary, gallery/avatar, kiểm tra và giới hạn ảnh |
| invoice_service | 8090 `/invoice` | Đọc/tạo chứng từ theo booking và chủ tài khoản |
| search_service | 8089 `/search` | Elasticsearch, Redis, đồng bộ snapshot từ tour |
| ai_service | 8091 `/ai` | Spring AI/Groq, hội thoại, chính sách và hỗ trợ booking qua API |

Mỗi thư mục có `pom.xml` riêng; không có parent build tổng tại Backend. Module nghiệp vụ dùng Java 21 / Boot 3.2.5 / Cloud 2023.0.1; AI dùng Boot 3.5.16 / Spring AI 1.1.8. Run lớp `<Tên>ServiceApplication` hoặc `ApiGatewayApplication` trong IDE, working directory là module.

Các service nghiệp vụ cùng đọc/ghi **dbtour_v2**. AI không đọc SQL; tour/booking cung cấp API đọc có giới hạn. Việc tách tiến trình/service hiện tại chưa đồng nghĩa database-per-service. Không có module notification:8082 hoặc schedule:8085; route trỏ tới hai cổng đó đã được bỏ khỏi gateway, gọi vào sẽ nhận 404.

## Database

Trước khi chạy lần đầu, import **[database_all.sql](data_seed/database_all.sql)** vào dbtour_v2 mới/trống. File chứa đủ schema JPA và JDBC: 31 bảng, users.status, reservation, outbox, dời lịch/audit, đối soát và khóa media. JPA `ddl-auto` đã đặt `none` để Hibernate không tự tạo/sửa bảng, nên bước import này là bắt buộc; file đã hợp nhất toàn bộ thay đổi schema, không có migration riêng.

dbtour_v2 đã đủ schema/dữ liệu thì giữ nguyên, không import lại mỗi lần chạy. File tổng không nâng cấp/merge vào DB đang có bảng. Chi tiết dữ liệu và tài khoản tại [README database](data_seed/README.md).

## Cấu hình local

Mặc định datasource dùng `jdbc:mysql://localhost:3306/dbtour_v2`, username `root`. Đổi URL/username bằng `DB_URL`/`SPRING_DATASOURCE_USERNAME` (hoặc property Spring chuẩn `spring.datasource.url`/`spring.datasource.username`), mật khẩu đọc từ `DB_PASSWORD`.

| Biến / property | Module cần | Ý nghĩa |
|---|---|---|
| `DB_PASSWORD` | Bảy service có datasource | Mật khẩu DB local, có thể rỗng nếu DB local của bạn thực sự không đặt mật khẩu |
| `JWT_SIGNER_KEY` | Auth và các service nghiệp vụ xác minh JWT | Cùng khóa HS512, tối thiểu 64 byte UTF-8, giữ cục bộ |
| `GOOGLE_CLIENT_ID` | Auth | Audience của Google ID token; mặc định là client demo, override khi dùng OAuth client khác |
| `DB_URL` | Bảy service có datasource | URL JDBC, mặc định `jdbc:mysql://localhost:3306/dbtour_v2`; compose trỏ `host.docker.internal` |
| `DB_POOL_MAX`, `DB_POOL_MIN` | Bảy service có datasource | Kích thước Hikari pool (mặc định 10/2). Bảy service dùng chung một MySQL nên tổng pool phải thấp hơn `max_connections` (151) |
| `DB_POOL_TIMEOUT_MS` | Bảy service có datasource | Thời gian chờ kết nối trước khi báo lỗi (mặc định 10000 ms) |
| `JAVA_XMX` | Container backend | Nới heap cho mọi container Docker khi máy nhiều RAM (ví dụ `512m`–`1g`); để trống dùng mặc định trong Dockerfile |
| `TOUR_IMAGE_REFRESH_INTERVAL_MS` | Tour | Chu kỳ đối soát snapshot ảnh tour (mặc định 300000 ms). Upload/sửa tour cập nhật snapshot ngay, chu kỳ này chỉ để chỉnh sai lệch |
| `TOUR_RANDOM_TTL_MS` | Tour | Cache widget 3 tour ngẫu nhiên ở trang chủ; mặc định 0 = random mới mỗi lần tải |
| `TOUR_IMAGE_SNAPSHOT_FILE` | Tour | Đường dẫn snapshot ảnh tour (mặc định `data/tour-images.json`); compose trỏ vào volume để ảnh còn sau khi rebuild |
| `SEARCH_SYNC_DELAY_MS`, `SEARCH_SYNC_INITIAL_DELAY_MS` | Search | Chu kỳ đồng bộ danh mục (mặc định 60000 ms) và độ trễ lần đầu (2000 ms). `POST /search/tours/sync` vẫn ép đồng bộ ngay khi cần |
| `ES_URIS`, `REDIS_HOST`, `REDIS_PORT` | Search | Địa chỉ Elasticsearch/Redis (mặc định `http://localhost:9200`, `localhost`, `6379`); compose trỏ tên service nội bộ bằng property Spring chuẩn `SPRING_ELASTICSEARCH_URIS`/`SPRING_DATA_REDIS_HOST`, cả hai cách đặt đều có hiệu lực |
| `MAIL_POLL_DELAY_MS` | Booking | Nhịp thăm outbox SMTP (mặc định 5000 ms); mỗi lượt vẫn xử lý cả batch |
| `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` | Service có datasource | Override URL/user bằng property Spring chuẩn khi cần |
| `VNPAY_HASH_SECRET`, `VNPAY_TMN_CODE` | Payment | Bắt buộc: payment_service từ chối khởi động nếu thiếu một trong hai (không dùng secret hard-code) |
| `VNPAY_RETURN_URL`, `VNPAY_PAY_URL` | Payment | URL trả về và endpoint thanh toán; mặc định là bộ sandbox demo |
| `CORS_ALLOWED_ORIGINS` | Gateway | Danh sách origin được phép, phân tách bằng dấu phẩy; mặc định các cổng dev của Vite |
| `AUTH_LOGIN_LIMIT`, `AUTH_REGISTER_LIMIT`, `AUTH_GOOGLE_LIMIT`, `AUTH_REFRESH_LIMIT`, `AUTH_PASSWORD_CHANGE_LIMIT` | Gateway | Số lượt mỗi IP mỗi phút cho từng endpoint trước khi nhận 429 (mặc định 20/5/10/30/10); mỗi endpoint có bucket riêng |
| `AUTH_CONNECT_TIMEOUT_MS`, `AUTH_RESPONSE_TIMEOUT_MS` | Gateway | Timeout gọi auth khi introspect JWT (mặc định 2000/3000 ms); quá hạn/auth sập trả 503 |
| `AUTH_BREAKER_FAILURES`, `AUTH_BREAKER_OPEN_MS` | Gateway | Ngưỡng lỗi liên tiếp và thời gian mở mạch (mặc định 5 lỗi / 10 giây); khi mở, gateway trả 503 ngay mà không gọi auth |
| `AUTH_BASE_URL`, `AUTH_INTROSPECT_CACHE_SECONDS` | Gateway | Địa chỉ auth và thời gian cache kết quả introspect (mặc định `http://localhost:8080`, 10 giây) |
| `CLOUDINARY_API_KEY`, `CLOUDINARY_API_SECRET` | File | Upload/quản lý ảnh; cloud-name theo `cloudinary.cloud-name` |
| `FILE_DOWNLOADS_PER_MINUTE` | File | Giới hạn số lượt gọi `/media/download/**` theo IP mỗi phút (mặc định 60; đặt 0 để tắt khi có CDN) |
| `MOMO_PARTNER_CODE`, `MOMO_ACCESS_KEY`, `MOMO_SECRET_KEY` | Payment | Credential MoMo AIO v2, tuỳ chọn: thiếu thì checkout MoMo báo "chưa cấu hình" chứ service vẫn khởi động. `MOMO_PAY_URL`, `MOMO_RETURN_URL`, `MOMO_IPN_URL` override endpoint mặc định |
| `MAIL_*` | Booking; internal key còn cần gateway | SMTP, test mode và API n8n; xem README mail |
| `GROQ_API_KEY`, `AI_*` | AI | Provider và tùy chọn AI; xem README ai_service |

Các service datasource tự import file `.local/<module>.properties` từ working directory module hoặc thư mục Backend. Nơi dùng chung thuận tiện là `.local/` ở thư mục Backend:

```text
.local/
├── auth_service.properties
├── booking_service.properties
├── payment_service.properties
├── pricing_service.properties
├── tour_service.properties
├── file_service.properties
├── invoice_service.properties
└── search_service.properties
```

Ví dụ cấu trúc file local, cần thay placeholder cục bộ trước khi dùng, không ghi đè file đang có:

```properties
DB_PASSWORD=<mat-khau-mysql-local>
JWT_SIGNER_KEY=<cung-mot-khoa-ngau-nhien-toi-thieu-64-byte-cho-cac-service>
# Chỉ thêm nếu cần đổi mặc định:
# spring.datasource.url=jdbc:mysql://127.0.0.1:3306/dbtour_v2
# spring.datasource.username=root
```

Riêng `.local/payment_service.properties` cần thêm hai khóa VNPay bắt buộc (thiếu một trong hai thì service từ chối khởi động) và credential MoMo nếu muốn bật MoMo:

```properties
VNPAY_HASH_SECRET=<secret-sandbox-vnpay>
VNPAY_TMN_CODE=<tmn-code-sandbox-vnpay>
# Tuỳ chọn, chỉ cần khi bật thanh toán MoMo:
# MOMO_PARTNER_CODE=<partner-code>
# MOMO_ACCESS_KEY=<access-key>
# MOMO_SECRET_KEY=<secret-key>
```

File service thêm Cloudinary credential tương ứng. Giá trị môi trường tiến trình cũng có thể đặt trong Run Configuration. Gateway không tự import các file `.local/<module>.properties` này: truyền `MAIL_INTERNAL_API_KEY` vào môi trường gateway khi bật workflow. `.env.mail.example` chỉ mô tả biến; chỉ AI có bộ nạp riêng GROQ_API_KEY từ `.env`.

## Chạy và build

Tại từng module:

```powershell
mvn spring-boot:run
# Hoặc đóng gói rồi chạy JAR tương ứng trong target:
mvn -DskipTests package
```

Các module cũ có `mvnw.cmd` nếu không dùng Maven đã cài. AI chạy Maven/IDE trực tiếp, không cần launcher PS1. Khi sửa Java/dependency cần rebuild/restart đúng service bị đổi; không chạy trùng bản IDE và JAR trên cùng cổng.

MySQL mở trước; pricing/file/tour/auth sẵn sàng trước các flow phụ thuộc. Search cần Elasticsearch/Redis; tại `search_service`:

```powershell
docker compose up -d elasticsearch redis
```

Search đọc snapshot từ `http://localhost:8086/tour/tours/search-snapshot`, bắt đầu thử sau khoảng 2 giây và lặp sau 60 giây mặc định; endpoint sync của search ép chạy ngay. Lỗi đồng bộ giữ index cũ và sẽ thử lại. Giá/index/cache không phải dữ liệu cần import SQL riêng. Snapshot là feed nội bộ: khi chạy khác loopback (Docker/profile search) đặt cùng `CATALOG_INTERNAL_KEY` cho tour_service và search_service (`SEARCH_SOURCE_KEY`), auth_service cũng nhận cùng biến; để trống chỉ chấp nhận lời gọi loopback.

Chạy native là luồng chính khi phát triển. Khi cần chạy cả hệ thống bằng container, dùng `docker-compose.yml` ngay trong repo Backend (cần checkout repo frontend `../Frontend_TourDuLich` cạnh nó): file dựng gateway, 7 service nghiệp vụ và frontend (search/AI/mail theo profile), đọc bí mật từ `.local/*.properties`, nối MySQL trên host qua `host.docker.internal`, có healthcheck bằng `nc -z`. Các URL nội bộ đã được truyền bằng biến môi trường (`*_SERVICE_URL`, `APP_SERVICES_*`, `APP_ADVICE_*`, `SPRING_ELASTICSEARCH_URIS`, `SPRING_DATA_REDIS_HOST`).

## Quy ước dữ liệu sau khi tắt open-in-view

Bảy service có datasource đặt `spring.jpa.open-in-view: false`, nên session JPA chỉ sống trong transaction của service/repository:

- DTO đọc quan hệ LAZY (`@ManyToOne`, `@ManyToMany`, `@OneToMany`) phải được map bên trong `@Transactional`, hoặc nạp sẵn bằng `@EntityGraph`/`fetch join` như `TourRepository`, hay khởi tạo tường minh bằng `Hibernate.initialize` trong transaction như `RoleService`.
- Controller không serialize trực tiếp entity có collection LAZY; nếu buộc phải trả entity, nạp collection trong transaction trước khi return.
- Lời gọi HTTP ra service khác (pricing, file, Google, booking) đặt trước transaction để không giữ connection chờ mạng.
- Vi phạm quy ước này lỗi ngay bằng `LazyInitializationException` (fail fast), không âm thầm chậm.

Pool 10/2 với `connection-timeout` 10 giây nghĩa là khi vượt 10 truy vấn đồng thời trên một service, request xếp hàng và chỉ báo lỗi sau 10 giây; mức này đủ cho demo nhiều người và không chạm `max_connections`.

## Nghiệp vụ cần hiểu khi demo

- Booking CONFIRMED không tự có nghĩa đã trả tiền. Kiểm tra reservation, payment, invoice và VNPay transaction tương ứng.
- Còn hơn 7 ngày lịch: hạn thanh toán là min(ngày đặt + 6 ngày, ngày đi − 6 ngày), lúc 08:00 Việt Nam. Nhắc ngày đặt + 3 nếu không quá hạn − 1, mốc còn lại là hạn − 1 và gộp nếu trùng. Còn tối đa 7 ngày: checkout tối đa 15 phút, không vượt giờ đi.
- Hết hạn trả chỗ theo worker backend; mail tắt không tắt giữ/trả chỗ. n8n không quyết định số ghế hoặc trạng thái thanh toán.
- Khách PAID chỉ yêu cầu đổi trước giờ đi ít nhất 72 giờ. Dời shop cần lý do bắt buộc và xử lý từng booking; không sửa ngày trực tiếp để bỏ qua quy trình. Hoàn tiền được ghi nhận theo chứng từ/tham chiếu, chưa phải API chuyển tiền tự động.
- Users có ACTIVE/LOCKED/ARCHIVED. Khách không tự khóa/xóa tài khoản. Tài khoản có lịch sử cần lưu trữ thay vì xóa thật; xóa thật chỉ cho tài khoản đã khóa và không có lịch sử nghiệp vụ. Có bảo vệ tài khoản đang thao tác và tính liên tục của admin.
- JWT mang mã quyền (`PERM:...`) và `sessionEpoch`, nên mọi service chỉ kiểm tra lại bằng đúng một query. Vì vậy khi admin đổi vai trò của một tài khoản, đổi tên/bật-tắt/danh sách quyền của một vai trò, hoặc gỡ một quyền khỏi vai trò, hệ thống ghi thêm một sự kiện vào `user_account_event` cho từng tài khoản bị ảnh hưởng: epoch tăng làm mọi token cũ (kể cả còn hạn) hết hiệu lực và các tài khoản đó phải đăng nhập lại. Chỉ cấp thêm quyền thì không thu hồi — lần refresh kế tiếp sẽ mang quyền mới. Thao tác này chỉ diễn ra trong cùng giao dịch với việc sửa quyền, nên sửa quyền thất bại thì không có ai bị đăng xuất oan.
- Upload tour nhận tối đa 10 ảnh/lượt, 5 MiB/ảnh, tổng 25 MiB; avatar tối đa 2 MiB. Backend kiểm tra chữ ký ảnh và kích thước pixel cho JPEG/PNG/WebP. Logo SVG trong source là tài nguyên tĩnh, không có nghĩa API chấp nhận SVG người dùng upload.

## API tiêu biểu

Qua gateway, prefix `http://localhost:8888/api/v1`:

| Method / path | Mục đích |
|---|---|
| `POST /auth/users/register` | Đăng ký role khách mặc định từ DB |
| `POST /auth/users/login` | Đăng nhập bằng email/password |
| `POST /auth/users/refresh` | Làm mới phiên |
| `GET /tour/tours/{id}` | Chi tiết tour |
| `GET /tour/tours/search` | Tìm/lọc theo API tour |
| `POST /booking/internal/mail/reminders/dispatch` | n8n lập lượt, dùng header key riêng |
| `POST /ai/chat` | Chat theo conversationId/requestId |
| `GET /ai/policies` | Chính sách đã bật theo version |
| `POST /ai/booking-help` | Tra cứu booking chính chủ, cần JWT |

Body login:

```json
{"email":"admin@example.com","password":"admin123"}
```

Không gửi roleId từ frontend để tự quyết định role đăng nhập. Quyền backend kiểm tra trên API và quyền owner; menu frontend chỉ là lớp hiển thị bổ sung.

## Kiểm thử

Tại module cần kiểm tra:

```powershell
mvn '-Dtest=*Test' test
```

Lệnh trên chọn lớp kết thúc `Test`. Không có lệnh aggregate từ Backend; chạy tương ứng từng module. Bài `*Tests`, DB thật, SMTP/Groq live có cấu hình/điều kiện riêng. Mặc định kiểm thử H2/mock không chứng minh đã gửi email, thanh toán hay gọi provider thật. Không trỏ biến test DB vào dbtour_v2 đang dùng.

`src/test/resources/**/*.sql` là fixture, không phải migration phải import khi cài app. Cơ chế nạp fixture booking/payment đã chuyển vào classpath để không phụ thuộc các migration đã xóa.

## Theo dõi và tài liệu liên quan

- Cổng đã dùng: xem PID/listener, dừng đúng bản service mình đang chạy thay vì mở bản thứ hai. Lỗi 8083/8090 thường do IDE và helper mail cùng khởi động. Kiểm tra tổng thể bằng `scripts\check-env.ps1` ở thư mục Backend (JDK/Maven/Node, MySQL, Docker, key trong `.local`, cổng đang chiếm).
- Thiếu DB/key: xem config import và environment của chính tiến trình Java; không chỉ kiểm tra biến ở terminal khác.
- Mail: đọc trạng thái `mail_outbox`, log booking service và n8n Executions. Hiện chưa có dashboard admin mail.
- [Email/n8n](docs/mail/README.md) · [AI service](ai_service/README.md) · [Tài liệu AI](docs/ai/README.md) · [Database](data_seed/README.md).

Dữ liệu `.local/backups`, `.local/recovery`, log và target không phải source cần triển khai. Giữ bí mật config và bản sao DB ngoài phần source chia sẻ. Source hiện có MoMo/route legacy và Dockerfile rời; không ghi nhận chúng như chức năng production hoàn thiện.
