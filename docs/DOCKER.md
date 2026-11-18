# Vận hành bằng Docker Compose

Quy trình chuẩn bị mã nguồn, copy file cấu hình, điền credential và khởi chạy nằm trong [README — Cách 1: Docker Compose](../README.md#cách-1-chạy-toàn-bộ-bằng-docker-compose). Cách 1 bật đồng thời frontend, cả 10 ứng dụng Spring Boot, MySQL, Elasticsearch, Redis và n8n bằng các profile search/ai/mail; không cần mở IntelliJ hoặc bật XAMPP. Docker Desktop phải hoạt động trong thời gian sử dụng.

Các file `.local/*.properties.example` có trong repo, nhưng file `.properties` để chạy phải được tạo từ mẫu theo [hướng dẫn cấu hình](../.local/README.md). Chỉ các mẫu không có secret và tài liệu được theo dõi bởi Git.

## Địa chỉ và cổng

| Thành phần | Địa chỉ từ host | Địa chỉ trong Compose |
|---|---|---|
| Website/Nginx | `http://localhost` | `frontend:80` |
| Gateway | `http://localhost:8888/api/v1` | `api_gateway:8888` |
| MySQL | `127.0.0.1:3307` | `mysql:3306` |

MySQL dùng schema `dbtour_v2`, user `sgu_tour`, password từ `MYSQL_PASSWORD` trong `.env`. `MYSQL_PORT` đổi cổng host nếu 3307 bị chiếm. Nếu cổng 80 bị phần mềm khác chiếm, giải phóng cổng hoặc sửa port frontend; khi đổi địa chỉ website, cập nhật `PUBLIC_BASE_URL` và cấu hình origin/OAuth liên quan.

## Kết hợp MySQL Docker với IntelliJ/Maven

Cách này chỉ đưa database vào Docker; ứng dụng vẫn chạy trên host theo Cách 2 trong README. Cần JDK/Maven/Node.js và Docker Desktop. Không bật toàn bộ stack cùng lúc.

Tại backend, chuẩn bị `.env` theo README rồi chạy:

```powershell
docker compose up -d mysql
docker compose ps mysql
```

Sau khi database healthy, cấu hình cả bảy file `.local/<module>.properties`:

```properties
DB_URL=jdbc:mysql://localhost:3307/dbtour_v2?allowPublicKeyRetrieval=true&useSSL=false
spring.datasource.username=sgu_tour
DB_PASSWORD=gia_tri_MYSQL_PASSWORD_trong_env
```

Sau đó chạy backend bằng IntelliJ/Maven, frontend bằng npm. Không cần MySQL XAMPP. Database đang dùng là volume MySQL Docker, không phải dữ liệu XAMPP trên 3306. Có thể kết nối IDE/Workbench nếu muốn; công cụ này không bắt buộc.

## Tạo dữ liệu và giữ dữ liệu

MySQL 8.4 dùng named volume `mysql_data`. Khi volume trống, entrypoint chính thức chạy `data_seed/database_all.sql` một lần, dựng schema, quyền và dữ liệu mẫu. Backend chỉ khởi động sau khi database đã import xong và healthcheck truy vấn được bảng nghiệp vụ. Hibernate giữ `ddl-auto=none`.

`docker compose stop` / `docker compose down` giữ dữ liệu; lần bật sau không import lại. **Không chạy `docker compose down -v` nếu cần giữ dữ liệu**, vì tùy chọn đó xóa cả volume database. Database XAMPP không được tự di chuyển hay chỉnh sửa.

SQL hiện tại chỉ dành cho DB mới/trống. Nếu import lần đầu bị lỗi/gián đoạn, đọc `docker compose logs mysql`, sao lưu dữ liệu cần giữ và xử lý trên volume demo riêng; không ép bỏ qua lỗi hoặc import chồng. Thay mật khẩu trong `.env` sau khi volume đã có dữ liệu không tự đổi mật khẩu trong MySQL; phải đổi bằng SQL với quyền phù hợp hoặc dùng volume demo mới sau khi đã sao lưu.

Đây là cấu hình **local/demo**, có mật khẩu mặc định công khai trong example và chỉ publish cổng trên loopback; không dùng nguyên trạng cho production.

## Khởi động và kiểm tra toàn bộ hệ thống

Sau khi hoàn tất cấu hình trong README:

```powershell
docker compose --profile search --profile ai --profile mail config -q
docker compose --profile search --profile ai --profile mail --parallel 1 build
docker compose --profile search --profile ai --profile mail up -d
docker compose --profile search --profile ai --profile mail ps
docker compose logs --tail=80 mysql search_service ai_service n8n
```

Các profile bật cùng nhau; thứ tự sẵn sàng vẫn do dependency/healthcheck quyết định. `--parallel 1` chỉ giới hạn build, không làm các container chạy lần lượt. n8n editor ở `http://localhost:5678`; workflow cần được import, cấu hình key và kích hoạt theo [hướng dẫn mail](mail/README.md). Gửi SMTP cần cấu hình riêng và `MAIL_ENABLED=true`.

Dừng hoặc bật lại toàn bộ phải dùng đủ profile:

```powershell
docker compose --profile search --profile ai --profile mail down
docker compose --profile search --profile ai --profile mail up -d
```

`search_service/docker-compose.yml` là stack Elasticsearch/Redis độc lập cho phát triển, không dùng thay lệnh toàn hệ thống. Với cách 2, có thể chỉ bật các container hạ tầng cần thiết rồi chạy Java/npm trên host theo cấu hình tương ứng.
