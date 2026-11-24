# SGU Tour — Backend

[![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2%20%2F%203.5-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Architecture](https://img.shields.io/badge/Architecture-Microservices%20(Database--per--Service)-0A66C2)](#kiến-trúc-microservice)
[![Docker Compose](https://img.shields.io/badge/Docker-Compose-2496ED?logo=docker&logoColor=white)](docker-compose.microservice.yml)

## Giới thiệu

SGU Tour là hệ thống đặt tour du lịch tại Việt Nam, cung cấp trọn vẹn nghiệp vụ từ quản lý danh mục, giữ chỗ có thời hạn, thanh toán trực tuyến, phát hành hóa đơn, dời lịch/hoàn tiền đến thông báo và báo cáo vận hành.

Hệ thống được thiết kế theo kiến trúc **Microservices chuẩn Database-per-Service** gồm **12 ứng dụng Spring Boot** và **9 cơ sở dữ liệu MySQL 8.4 vật lý độc lập** (`sgu_auth` → `sgu_reporting`), kết hợp **RabbitMQ** (Transactional Outbox/Inbox + Saga), **Redis**, **Elasticsearch** và **Spring AI**.

Các năng lực cốt lõi:

- **Cô lập dữ liệu tuyệt đối (0 cross-DB SQL):** Mỗi service nghiệp vụ sở hữu schema, container MySQL và tài khoản DB riêng (`SHOW GRANTS` chỉ cấp quyền trên đúng DB của mình).
- **Bảo mật Fail-Closed:** JWT HS256/HS512 đồng bộ; kiểm tra phiên/thu hồi token qua `POST /internal/auth/sessions/validate`; toàn bộ `/internal/**` được bảo vệ bởi `Internal*AuthenticationFilter` và `X-Internal-*-Key`.
- **Đặt tour & Thanh toán tin cậy:** Chốt giá snapshot tại thời điểm đặt, giữ chỗ qua `TourInternalClient` (`departure_hold`), tự động giải phóng chỗ hết hạn, thanh toán VNPay/MoMo sandbox, đối soát thanh toán và phát sự kiện `payment.settled.v1` / `payment.refunded.v1` qua Outbox/Inbox idempotent.
- **Tìm kiếm & Trợ lý AI:** Đồng bộ danh mục sang Elasticsearch/Redis; tư vấn tour và tra cứu booking qua Spring AI + Groq.
- **Thông báo & Báo cáo tách biệt:** `notification_service` xử lý email outbox & DLQ bất đồng bộ; `reporting_service` tổng hợp chỉ số vận hành không PII qua RabbitMQ.

## Kiến trúc Microservice

```text
                         ┌───────────────────────────┐
                         │  Frontend (React + Nginx) │
                         └─────────────┬─────────────┘
                                       │ HTTPS / REST
                                       ▼
                         ┌───────────────────────────┐
                         │    api_gateway (:8888)    │  ◄── Redis (Rate Limit / Cache)
                         └─────────────┬─────────────┘
       ┌───────────────┬───────────────┼───────────────┬───────────────┐
       ▼               ▼               ▼               ▼               ▼
┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────┐
│auth_service │ │tour_service │ │booking_svc  │ │payment_svc  │ │invoice_svc  │
│  sgu_auth   │ │  sgu_tour   │ │ sgu_booking │ │ sgu_payment │ │ sgu_invoice │
└──────┬──────┘ └──────┬──────┘ └──────┬──────┘ └──────┬──────┘ └──────┬──────┘
       │               │               │               │               │
       └───────────────┴───────┬───────┴───────────────┴───────────────┘
                               │  Internal REST (Feign + X-Internal-*-Key)
                               │  & Async Events (RabbitMQ + Outbox / Inbox)
       ┌───────────────┬───────┴───────┬───────────────┬───────────────┐
       ▼               ▼               ▼               ▼               ▼
┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────┐
│pricing_svc  │ │file_service │ │notification │ │reporting_svc│ │search & ai  │
│ sgu_pricing │ │  sgu_file   │ │sgu_notific. │ │sgu_reporting│ │ ES / Groq   │
└─────────────┘ └─────────────┘ └─────────────┘ └─────────────┘ └─────────────┘
```

## Công nghệ

| Thành phần | Công nghệ | Vai trò |
|---|---|---|
| Ngôn ngữ | Java 21 | Nền tảng thực thi backend |
| Framework | Spring Boot 3.2 (AI service dùng 3.5) | Xây dựng và vận hành 12 microservices |
| API & Giao tiếp | Spring MVC, Spring Cloud Gateway, OpenFeign, RestClient | Định tuyến `/api/v1`, gọi đồng bộ giữa các service |
| Messaging & Saga | RabbitMQ 3.13, Transactional Outbox/Inbox | Truyền sự kiện bất đồng bộ, đảm bảo idempotency |
| Bảo mật | Spring Security, OAuth2 Resource Server, JWT, Internal Key Filters | Xác thực, phân quyền RBAC và bảo vệ `/internal/**` |
| Dữ liệu | 9 × MySQL 8.4 độc lập, Spring Data JPA, Spring JDBC | Cô lập dữ liệu theo từng bounded context |
| Tìm kiếm & Cache | Elasticsearch 8.15, Redis 7.2 | Lập chỉ mục danh mục tour và cache tìm kiếm |
| AI | Spring AI 1.1, Groq | Tư vấn tour và tra cứu đơn đặt tour |
| Media & Email | Cloudinary, Spring Mail (SMTP), Durable DLQ | Lưu trữ hình ảnh và gửi email bất đồng bộ |
| Kiểm thử & Đóng gói | JUnit 5, Mockito, Spring Test, H2, Docker Compose | 600+ unit/integration tests và điều phối container |

## Các module (12 Services)

| Module | Cổng / Context | Database sở hữu | Chức năng chính |
|---|---|---|---|
| `api_gateway` | `8888` | *(Redis)* | Định tuyến `/api/v1`, rate limiting, xác thực tập trung |
| `auth_service` | `8080` `/auth` (Socket.IO `8099`) | `sgu_auth` | Tài khoản, JWT, Google OAuth, RBAC, session validation |
| `tour_service` | `8086` `/tour` | `sgu_tour` | Tour, lịch trình, địa điểm, phương tiện, `departure_hold` |
| `pricing_service` | `8085` `/pricing` | `sgu_pricing` | Bảng giá theo nhóm tuổi và phụ thu |
| `booking_service` | `8082` `/booking` | `sgu_booking` | Đặt tour, chốt giá snapshot, giữ/trả chỗ, dời lịch, hoàn tiền |
| `payment_service` | `8083` `/payment` | `sgu_payment` | VNPay/MoMo sandbox, đối soát, `payment_outbox` |
| `invoice_service` | `8084` `/invoice` | `sgu_invoice` | Hóa đơn theo booking, `invoice_inbox` (`payment.settled.v1`) |
| `file_service` | `8087` `/file` | `sgu_file` | Upload/quản lý ảnh Cloudinary, `file_reference` & tombstone |
| `notification_service` | `8090` | `sgu_notification` | Gửi email outbox, template và durable DLQ |
| `reporting_service` | `8091` | `sgu_reporting` | Tổng hợp báo cáo vận hành (không PII) qua RabbitMQ |
| `search_service` | `8089` `/search` | *(Elasticsearch/Redis)* | Tìm kiếm danh mục tour tốc độ cao |
| `ai_service` | `8091` `/ai` | *(Stateless)* | Trợ lý AI tư vấn tour và tra cứu booking |

Mỗi module là một project Maven độc lập (có sẵn `./mvnw`).

## Khởi chạy nhanh (Microservice 9 DB)

```bash
cp .env.microservice.example .env.microservice
# Điền các secret ngẫu nhiên (>= 32 ký tự cho internal keys, >= 64 byte cho JWT_SIGNER_KEY)
docker compose -f docker-compose.microservice.yml --env-file .env.microservice up -d --build
```

Gateway mặc định lắng nghe tại `http://127.0.0.1:8888`. Các cổng DB nội bộ không publish ra host để đảm bảo bảo mật.

## Chuẩn bị

### Mã nguồn

Cài Git và clone hai repository từ `main` vào cùng một thư mục:

```bash
git clone --branch main https://github.com/DinhThai04/Backend_TourDuLich.git
git clone --branch main https://github.com/DinhThai04/Frontend_TourDuLich.git
cd Backend_TourDuLich
```

Compose sử dụng `../Frontend_TourDuLich` làm build context cho frontend:

```text
SGUTour/
├── Backend_TourDuLich/
└── Frontend_TourDuLich/
```

### Môi trường

- **Docker Compose:** cần Docker Desktop với Linux containers. Java, Maven, Node.js và MySQL được cung cấp trong các image; không cần chạy ứng dụng trong IntelliJ hay bật XAMPP.
- **IntelliJ/Maven:** cần JDK 21, Maven 3.9.x, MySQL/MariaDB và Node.js/npm nếu phát triển frontend. IntelliJ là IDE tùy chọn; có thể sử dụng Maven từ terminal.

## Cấu hình

Hoàn tất các cấu hình tương ứng trước khi khởi chạy. Mẫu được theo dõi bởi Git; file runtime và credential thực tế được giữ cục bộ.

### Cấu hình ứng dụng

Copy tám file `.local/*.properties.example` thành `.local/*.properties`. Có thể sử dụng PowerShell tại thư mục backend:

```powershell
Get-ChildItem -LiteralPath .local -Filter *.properties.example | ForEach-Object {
    $destination = Join-Path $_.DirectoryName $_.BaseName
    if (-not (Test-Path -LiteralPath $destination)) {
        Copy-Item -LiteralPath $_.FullName -Destination $destination
    }
}
```

Lệnh giữ nguyên file đã tồn tại. Sinh JWT key theo [hướng dẫn cấu hình cục bộ](.local/README.md#tạo-jwt-key-dùng-chung) rồi điền credential:

| Cấu hình | Nơi khai báo | Yêu cầu |
|---|---|---|
| `JWT_SIGNER_KEY` | Cả tám file `.local/<module>.properties` | Cùng key ngẫu nhiên tối thiểu 64 byte UTF-8 cho các service trong cùng hệ thống |
| `VNPAY_HASH_SECRET`, `VNPAY_TMN_CODE` | `.local/payment_service.properties` | Credential sandbox hợp lệ; payment yêu cầu cấu hình khi khởi động |
| `CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY`, `CLOUDINARY_API_SECRET` | `.local/file_service.properties` | Tài khoản quản lý hình ảnh |

Chỉ commit file mẫu không chứa secret. Các file `.properties` dùng thực tế và `.env` được Git bỏ qua. Chi tiết từng biến nằm trong [cấu hình hệ thống](docs/PROJECT.md).

### Cấu hình Docker Compose

Copy [.env.docker.example](.env.docker.example) thành `.env` tại thư mục backend, rồi thiết lập:

| Cấu hình | Mục đích |
|---|---|
| `MYSQL_PASSWORD`, `MYSQL_ROOT_PASSWORD`, `MYSQL_PORT` | Mật khẩu database/app user và root; cổng host mặc định 3307 |
| `CATALOG_INTERNAL_KEY` | Key ngẫu nhiên ít nhất 32 ký tự; Compose dùng chung cho tour/auth/search |
| `AI_ENABLED=true`, `GROQ_API_KEY` | Bật AI với Groq key hợp lệ |
| `MAIL_INTERNAL_API_KEY` | Key ngẫu nhiên ít nhất 32 ký tự dùng chung ở booking/gateway và workflow n8n |
| `MAIL_ENABLED`, `MAIL_TEST_MODE` | Bật SMTP sau khi cấu hình; giữ test mode khi trình diễn |
| `MAIL_TEST_RECIPIENT`, `MAIL_FROM`, `MAIL_SMTP_USERNAME`, `MAIL_SMTP_PASSWORD` | Hộp thư test, địa chỉ gửi và credential SMTP |
| `GOOGLE_CLIENT_ID` | OAuth client ID nếu dùng Google Sign-In; Compose truyền vào auth và frontend build |

Compose cấu hình datasource với host `mysql:3306`, schema `dbtour_v2`, user `sgu_tour` và password từ `MYSQL_PASSWORD`. Không cần sửa datasource trong `.local` cho cách chạy này. MySQL tự import [database_all.sql](data_seed/database_all.sql) một lần khi volume dữ liệu còn trống.

n8n khởi động cùng hệ thống; gửi email qua Spring cần SMTP hợp lệ và `MAIL_ENABLED=true`. Theo [hướng dẫn mail](docs/mail/README.md), import và kích hoạt workflow n8n sau lần khởi động đầu.

### Cấu hình IntelliJ/Maven

Sử dụng MySQL/MariaDB đang chạy và import [database_all.sql](data_seed/database_all.sql) vào **`dbtour_v2` mới/trống**. Nếu dùng XAMPP, chỉ cần bật MySQL; Apache cần khi sử dụng phpMyAdmin. Không import lại vào database đã có dữ liệu.

Đặt kết nối trong bảy file `.local/<module>.properties` theo database thực tế:

```properties
DB_URL=jdbc:mysql://localhost:3306/dbtour_v2
spring.datasource.username=root
DB_PASSWORD=mat_khau_mysql_dang_su_dung
```

`DB_PASSWORD` chỉ để rỗng nếu tài khoản database cho phép. `.env` của Compose không tự nạp vào Run Configuration IntelliJ. Search/AI/mail cần cấu hình tương ứng nếu phát triển các chức năng đó; xem [tổng quan hệ thống](docs/PROJECT.md).

## Khởi chạy

### Cách 1: Chạy toàn bộ bằng Docker Compose

Tại thư mục backend, sau khi hoàn tất cấu hình:

```bash
docker compose --profile search --profile ai --profile mail --parallel 1 build
docker compose --profile search --profile ai --profile mail up -d
docker compose --profile search --profile ai --profile mail ps
```

Các lệnh bật frontend, cả 10 ứng dụng Spring Boot, MySQL, Elasticsearch, Redis và n8n trong cùng hệ thống. `--parallel 1` chỉ giới hạn build; các container hoạt động đồng thời theo dependency/healthcheck. Docker Desktop cần duy trì hoạt động; không chạy thêm bản service trong IntelliJ hoặc frontend bằng npm.

- Website: **http://localhost**.
- Gateway: `http://localhost:8888/api/v1`.
- n8n: **http://localhost:5678**.
- MySQL từ host: `127.0.0.1:3307`, schema `dbtour_v2`, user `sgu_tour`.

Dừng hệ thống và bật lại bằng cùng các profile:

```bash
docker compose --profile search --profile ai --profile mail down
docker compose --profile search --profile ai --profile mail up -d
```

`down` giữ các named volume; `down -v` xóa cả dữ liệu trong volume. Không import lại SQL mỗi lần khởi động. Hướng dẫn workflow n8n, tài nguyên Docker, cổng và dữ liệu nằm trong [tài liệu Docker](docs/DOCKER.md).

### Cách 2: Chạy bằng IntelliJ/Maven và npm

Chạy các module cần thiết từ IntelliJ bằng lớp `*Application`, hoặc sử dụng Maven tại thư mục module. Ví dụ với auth_service:

```bash
cd auth_service
mvn spring-boot:run
```

Mỗi ứng dụng cần một tiến trình/Run Configuration riêng. Thứ tự cho luồng đặt tour đầy đủ:

```text
MySQL
  → auth_service, pricing_service, file_service, tour_service
  → booking_service, invoice_service, payment_service
  → api_gateway
```

Khi phát triển một module độc lập, chỉ mở module và các phụ thuộc liên quan. Search cần Elasticsearch/Redis và nguồn danh mục; AI cần các API đọc dữ liệu và Groq; mail cần SMTP và n8n nếu thử workflow. Cách sử dụng MySQL Docker cùng IntelliJ nằm trong [hướng dẫn kết hợp](docs/DOCKER.md#kết-hợp-mysql-docker-với-intellijmaven).

Chạy frontend theo [README frontend](https://github.com/DinhThai04/Frontend_TourDuLich#cách-2-npm). Website phát triển sử dụng `http://localhost:5173`, gọi API qua gateway 8888.

## Tài khoản trình diễn

Các tài khoản sau được tạo bởi SQL mẫu:

| Vai trò | Email | Mật khẩu |
|---|---|---|
| Admin | `admin@example.com` | `admin123` |
| Khách hàng | `user1@gmail.com` | `user12345` |

## Build và Docker

Mỗi module có `pom.xml` và Dockerfile riêng. Có thể tạo JAR từ thư mục module bằng:

```bash
mvn -DskipTests package
```

JAR được tạo trong `target/`; lệnh này bỏ qua test. Dockerfile dùng Maven/JDK để build rồi đưa JAR vào image JRE, giúp host chạy ứng dụng mà không cần cài bộ công cụ Java. Compose kết nối các service và phụ thuộc bằng tên trong mạng nội bộ.

Frontend sử dụng Dockerfile Node.js/Nginx trong repository riêng; Compose build từ thư mục frontend cạnh backend. Cấu hình frontend cần truyền ở thời điểm build, trong khi credential backend được đọc từ cấu hình runtime. Xem [hướng dẫn Docker](docs/DOCKER.md) để vận hành dữ liệu và container.

## Nâng cấp cơ sở dữ liệu hiện có

Khi dùng lại schema `dbtour_v2` hoặc volume MySQL đã có dữ liệu, chạy [004_travel_operations.sql](data_seed/migrations/004_travel_operations.sql) trên schema đó trước khi khởi động phiên bản mới. Bản nâng cấp tạo bảng điểm tập trung và nhật ký vận hành, đồng thời bổ sung chỉ mục cho báo cáo nếu còn thiếu. Có thể chạy lại cùng tập lệnh trên schema đã nâng cấp.

Khi tạo cơ sở dữ liệu từ đầu theo phần Khởi chạy, dữ liệu khởi tạo đã có cấu trúc này.

## Kiểm thử

Backend có 10 project Maven riêng, không có lệnh `mvn test` tổng tại thư mục gốc. Các lệnh Bash dưới đây chạy từ `Backend_TourDuLich` bằng Bash trên Linux/WSL hoặc Git Bash. Cần JDK 21 và Maven trong chính môi trường terminal đó; Docker Desktop không cung cấp sẵn Maven cho terminal host. Các lệnh `mvn -f ...` cũng dùng được trong PowerShell.

### Tổng quan các nhóm kiểm thử

| Module | Phạm vi chính | Lệnh chạy |
|---|---|---|
| `auth_service` | Đăng nhập/JWT, refresh/revoke, tài khoản, vai trò/quyền và đánh giá | `mvn -f auth_service/pom.xml test` |
| `tour_service` | Danh mục, địa điểm, lịch trình, sức chứa/lịch khởi hành và tư vấn tour | `mvn -f tour_service/pom.xml test` |
| `pricing_service` | Giá nguyên VND, kiểm tra đầu vào và cập nhật bảng giá | `mvn -f pricing_service/pom.xml test` |
| `booking_service` | Giữ/trả chỗ, expiry, giá snapshot, dời lịch, consent/refund và mail outbox | `mvn -f booking_service/pom.xml test` |
| `payment_service` | VNPay/MoMo, chữ ký callback, số tiền, replay và đối soát | `mvn -f payment_service/pom.xml test` |
| `invoice_service` | Quyền đọc hóa đơn, chủ đơn, tiền và xử lý luồng legacy | `mvn -f invoice_service/pom.xml test` |
| `file_service` | Avatar/gallery, chính sách upload, quyền tài khoản đích và cleanup ảnh | `mvn -f file_service/pom.xml test` |
| `api_gateway` | Route công khai/bảo vệ, introspection, giới hạn request và lỗi API | `mvn -f api_gateway/pom.xml test` |
| `search_service` | Đồng bộ danh mục, filter/phân trang và cache; một số ca cần ES/Redis | `mvn -f search_service/pom.xml test` |
| `ai_service` | Hội thoại, tiêu chí tìm kiếm, dữ liệu tư vấn, booking-help và adapter model | `mvn -f ai_service/pom.xml test` |

Phần lớn test dùng H2, mock hoặc HTTP fixture cục bộ; các ca có điều kiện môi trường có thể được đánh dấu skipped. Maven kết thúc thành công không có nghĩa mọi bài integration đã chạy. Xem `Tests run / Failures / Errors / Skipped` và báo cáo `<module>/target/surefire-reports/`.

### Chạy toàn bộ test backend

```bash
# Chạy tuần tự và dừng ngay khi một module thất bại.
modules=(payment_service booking_service tour_service file_service auth_service
         pricing_service invoice_service api_gateway search_service ai_service)
for module in "${modules[@]}"; do
  mvn -B --no-transfer-progress -f "$module/pom.xml" test || exit 1
done
```

Để chạy riêng một suite hoặc kiểm tra đóng gói:

```bash
mvn -f booking_service/pom.xml -Dtest=BookingLifecycleTest test
mvn -f payment_service/pom.xml -Dtest=ReservedVnPayTest test
mvn -f auth_service/pom.xml -DskipTests package
```

`-DskipTests package` chỉ kiểm tra build/đóng gói, không phải kết quả kiểm thử nghiệp vụ. Dockerfile hiện build với `-DskipTests`, nên `docker compose build` cũng không thay thế các lệnh test ở trên.

### Kiểm tra cross-service và hạ tầng

Hai nhóm cross-service sau được bật tường minh và dùng fixture H2 mặc định. Cần biên dịch test của payment/booking trước để có classpath cho kiểm tra liên module:

```bash
mvn -f payment_service/pom.xml test-compile &&
mvn -f booking_service/pom.xml test-compile &&
MAIL_CROSS_SERVICE_TEST=true mvn -f booking_service/pom.xml -Dtest=CrossServiceLifecycleTest test

CAPACITY_CROSS_SERVICE_TEST=true mvn -f tour_service/pom.xml -Dtest=DepartureCapacityTest test
```

Kiểm tra cấu hình Compose không khởi động container hoặc import dữ liệu:

```bash
docker compose --profile search --profile ai --profile mail config -q
docker compose -f search_service/docker-compose.yml config -q
```

Các test kết nối MySQL, Elasticsearch/Redis, handoff gateway hoặc SMTP có điều kiện và cấu hình riêng trong source test. Chỉ bật khi đã chuẩn bị môi trường cô lập phù hợp; không trỏ test vào database `dbtour_v2` đang sử dụng. Import SQL tổng không phải là test và không chạy lại trên dữ liệu hiện có. Test SMTP thật có thể gửi email; dùng đúng hộp thư do người kiểm thử kiểm soát theo [hướng dẫn mail](docs/mail/README.md).

### Kiểm tra frontend và script mail

Từ thư mục backend, khi đã cài Node.js/npm:

```bash
npm --prefix ../Frontend_TourDuLich ci
npm --prefix ../Frontend_TourDuLich test
npm --prefix ../Frontend_TourDuLich run lint
npm --prefix ../Frontend_TourDuLich run build
```

Script hồi quy launcher mail chạy bằng PowerShell trên Windows; dùng mock, không khởi động Java/Docker và không gửi email:

```powershell
powershell -NoProfile -File docs/mail/tests/Test-MailLauncher.ps1
```

CI tại [backend-ci.yml](.github/workflows/backend-ci.yml) kiểm tra test/build của 10 module, cú pháp Compose và import seed trên database cô lập. Các kiểm tra H2/mock không xác nhận thanh toán ngân hàng, Cloudinary, Google, Groq hoặc SMTP thực tế; cần kiểm thử tích hợp riêng cho các dịch vụ đó.

### Kiểm tra bảo mật trước khi commit

Chạy preflight để kiểm tra cấu hình local; script không in giá trị credential:

```powershell
powershell -NoProfile -File scripts/check-env.ps1
```

Kiểm thử cơ chế đổi JWT dùng fixture riêng, không sửa cấu hình runtime:

```powershell
powershell -NoProfile -File scripts/tests/Test-LocalJwtRotation.ps1
```

Khi đã cài [Gitleaks](https://github.com/gitleaks/gitleaks), có thể kiểm tra nội dung đã stage và lịch sử Git:

```bash
gitleaks git --pre-commit --staged --redact=100 .
gitleaks git --log-opts="--all" --redact=100 .
```

Lệnh đầu chỉ kiểm tra index; index rỗng không chứng minh working tree hoặc lịch sử sạch. Đối chiếu thêm file chưa được theo dõi, diff và các cảnh báo fixture/mẫu trước khi commit. Không đưa secret vào file mẫu hoặc ghi đè cảnh báo bằng cách loại toàn bộ thư mục test khỏi phạm vi quét. Đổi JWT theo [hướng dẫn cấu hình cục bộ](.local/README.md#thay-jwt-key-đang-sử-dụng); credential nhà cung cấp được thay/thu hồi tại tài khoản tích hợp.

## Tài liệu tham khảo

- [Cấu hình cục bộ](.local/README.md)
- [Kiến trúc và cấu hình hệ thống](docs/PROJECT.md)
- [Docker và dữ liệu](docs/DOCKER.md)
- [Microservice — kiến trúc và lộ trình](docs/MICROSERVICES.md)
- [Schema và dữ liệu mẫu](data_seed/README.md)
- [Email/n8n](docs/mail/README.md)
- [AI service](ai_service/README.md)
