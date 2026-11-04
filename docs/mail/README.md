# SGU Tour — Spring Email và n8n

Hướng dẫn vận hành hiện tại, đối chiếu source ngày **01/11/2026**. Database mail/giữ chỗ đã có trong **[SQL tổng](../../data_seed/database_all.sql)**; không import thêm SQL mail hay fixture test.

## Ai làm việc gì?

```text
Đặt tour / VNPay / hủy / hết hạn / dời lịch
    -> backend hoàn tất transaction, ghi sự kiện mail_outbox
    -> worker booking_service kiểm tra điều kiện -> Spring Email -> SMTP

n8n Manual Trigger hoặc Schedule mỗi giờ
    -> POST API nội bộ qua gateway, dùng X-Internal-Mail-Key
    -> booking_service chọn mốc nhắc hợp lệ, ghi outbox có event key
    -> cùng worker Spring Email gửi SMTP
```

n8n không tự truy cập DB, không gửi SMTP trong workflow hiện tại và không quyết định số chỗ/thanh toán. Mail giao dịch từ backend không phụ thuộc workflow đã Publish hay chưa. Schedule n8n chỉ tự chạy khi workflow được bật và n8n/backend đang online; chạy thủ công vẫn được khi chưa Publish.

Nội dung email mang thương hiệu SGU Tour, có text/HTML, logo và ảnh tour khi dữ liệu ảnh phù hợp. Thiếu ảnh không được coi là lý do bỏ toàn bộ thư.

## Bảng và thời điểm

| Dữ liệu | Chức năng |
|---|---|
| mail_outbox | Event key, người nhận/payload, lịch gửi, lease, số lần thử và kết quả |
| booking_reservation | Số ghế giữ, departure snapshot, deadline và hai mốc nhắc; không chỉ là bảng email |
| payment_reconciliation | Giao dịch cần đối soát; planner tránh xác nhận/nhắc sai khi tiền chưa thống nhất |

Các bảng audit/dời lịch/đối soát mới khác cũng có trong SQL tổng, không thêm bảng reminder riêng. Bảng giữ chỗ và đối soát phục vụ nghiệp vụ ngay cả khi tắt SMTP.

Theo múi giờ Việt Nam:

- Còn hơn 7 ngày lịch khi đặt: deadline = min(ngày đặt + 6 ngày, ngày khởi hành − 6 ngày), lúc **08:00**. Nhắc 1 ngày đặt + 3 nếu không sau deadline − 1; nhắc 2 deadline − 1, gộp nếu trùng.
- Còn tối đa 7 ngày lịch: CHECKOUT tối đa 15 phút và không vượt giờ khởi hành; cần thanh toán để hoàn tất.
- Booking đã trả tiền và chứng từ nhất quán: nhắc khởi hành **trước 5, 3, 1 ngày**, mốc 08:00. Không nhắc chưa thanh toán nữa khi đã trả tiền.
- Worker hết hạn riêng của backend xử lý khoảng mỗi 60 giây, lần đầu sau khoảng 5 giây. Worker SMTP thăm outbox mặc định mỗi 5 giây khi bật, mỗi lượt xử lý tối đa `batch-size` (mặc định 10) thư; hàng đợi lớn hơn bị giới hạn bởi nhịp này.
- Workflow mẫu chạy **mỗi giờ**, không chỉ 08:00. Planner chọn mốc gần nhất còn hợp lệ khi máy bật lại, không gửi dồn mọi mốc đã lỡ; không tự gia hạn hạn thanh toán vì máy tắt.

## Cấu hình và mở backend

Cần MySQL, schema tổng và các service của luồng bạn muốn thử. Mở Docker Desktop/n8n nếu dùng workflow container.

### Container n8n

Repo có sẵn service `n8n` trong `docker-compose.yml` (profile `mail`, chỉ bind `127.0.0.1:5678`). Tại thư mục Backend:

```powershell
docker compose --profile mail up -d n8n
```

Container này dùng chung mạng compose với backend nên workflow gọi `http://api_gateway:8888`, `http://booking_service:8081`, `http://auth_service:8099` (tên service), không dùng `localhost` hay `host.docker.internal`. Editor ở `http://127.0.0.1:5678`; volume `n8n_data` giữ workflow nhưng xóa volume là mất credential. Compose khai báo thêm `N8N_SGU_GATEWAY_URL`, `N8N_SGU_BOOKING_URL`, `N8N_SGU_AUTH_URL` đúng bằng các URL trên để dùng khi bạn đổi workflow sang `$env` (chỉ hoạt động khi n8n cho phép đọc env trong node).

Service n8n trong repo có các đặc điểm sau; không cần file compose nào bên ngoài đồ án:

- Chỉ bind `127.0.0.1:5678` — không mở ra LAN.
- `N8N_SECURE_COOKIE=false` để đăng nhập editor qua HTTP local; bật HTTPS thì bỏ cờ này.
- Healthcheck `/healthz`, log xoay vòng 10 MB × 3 file, dừng mềm 30 giây.
- Credential và workflow nằm trong volume `n8n_data`; xóa volume là mất, không commit dữ liệu này.
- Diagnostics tắt (`N8N_DIAGNOSTICS_ENABLED=false`).

### Cách dùng helper local Windows đang có

Tại thư mục Backend:

```powershell
.\docs\mail\Setup-MailLocal.ps1 -CopyKey
# Dừng bản gateway/booking/payment/invoice cũ trước, rồi:
.\docs\mail\Start-MailBackend.ps1 -Build
# Khi muốn dừng các tiến trình do helper ghi nhận:
.\docs\mail\Stop-MailBackend.ps1
```

Setup hỏi Gmail App Password, giữ key hiện có khi chạy lại và copy key vào clipboard. Cấu hình được mã hóa theo user Windows tại `%LOCALAPPDATA%\J2eeTourMail\config.json`, không in key ra terminal. Hộp thư test là địa chỉ bạn nhập khi chạy setup (chạy lại script để đổi), không có địa chỉ cá nhân gắn sẵn trong mã nguồn. Không nhập mật khẩu đăng nhập Gmail thường thay App Password.

Start helper build/chạy **booking, payment, invoice, gateway** bằng JAR, nạp biến mail cho tiến trình con. Các service auth/tour/pricing/file vẫn cần mở riêng khi thao tác toàn bộ web. Log nằm tại `%LOCALAPPDATA%\J2eeTourMail\logs`. Đóng terminal không tự dừng các Java process này; không mở thêm bản IDE trùng cổng.

Tùy chọn `-StartN8n` của helper chạy `docker compose -f docker-compose.yml --profile mail up -d n8n` ngay trong repo, dùng chung mạng compose với backend; không cần compose ngoài đồ án. Preflight của helper nhắc dùng SQL tổng `data_seed/database_all.sql`, không còn migration 001/003.

### Hoặc chạy Spring Boot/IDE trực tiếp

Đặt các biến từ [booking_service/.env.mail.example](../../booking_service/.env.mail.example) trong môi trường/Run Configuration của **booking_service**. Đây là file mô tả, Spring không tự nạp bằng cách copy thành `.env`.

| Biến chính | Tác dụng |
|---|---|
| MAIL_ENABLED | false mặc định; true mới chạy worker SMTP |
| MAIL_TEST_MODE | true mặc định, chỉ cho đúng người nhận test |
| MAIL_TEST_RECIPIENT, MAIL_FROM | Hộp thư test và địa chỉ gửi |
| MAIL_SMTP_USERNAME, MAIL_SMTP_PASSWORD | Tài khoản SMTP/App Password |
| MAIL_SMTP_HOST, MAIL_SMTP_PORT | Mặc định smtp.gmail.com:587, STARTTLS |
| MAIL_INTERNAL_API_KEY | Cùng key tối thiểu 32 ký tự ở booking **và gateway** |
| MAIL_FRONTEND_BASE_URL | URL liên kết về web, mặc định localhost:5173 |
| MAIL_BRAND | SGU Tour |

`docker-compose.yml` chuyển tiếp toàn bộ biến `MAIL_*` ở bảng trên (SMTP, test mode, batch, URL frontend) từ môi trường máy host vào booking service. Bắt buộc export `MAIL_INTERNAL_API_KEY` (≥32 ký tự) trước khi chạy `docker compose --profile mail up`; nếu để trống, endpoint dispatch trả 503 và n8n không gửi được mail. Credential SMTP cũng phải export trên host, không ghi vào compose.

Các biến DB/JWT của service vẫn cần cấu hình theo [README backend](../../README.md). Setup helper lưu config nhưng không tự truyền environment vào các service bạn Run bằng IDE; chọn cách chạy/cấu hình nhất quán.

Test mode **không đổi địa chỉ khách thành địa chỉ của bạn** rồi gửi. Mail ngoài MAIL_TEST_RECIPIENT sẽ bị bỏ qua/không lập tùy giai đoạn xử lý. Khi thử email, đặt booking bằng tài khoản có email thuộc bạn; không dùng user1@gmail.com…user10@gmail.com trong seed để thử gửi thật.

## Import và chạy workflow n8n

1. Import [n8n-reminders.workflow.json](n8n-reminders.workflow.json). Không thêm node Send Email cho workflow này.
2. Trong node **Cấu hình**:
   - `baseUrl`: giữ `http://api_gateway:8888` khi n8n chạy trong compose của repo (profile `mail`) — dùng tên service, không dùng `localhost`/`host.docker.internal`; n8n chạy native trên Windows thì đổi thành `http://localhost:8888`.
   - `dryRun`: **Boolean true** để kiểm tra trước.
   - `batchSize`: 50, `afterBookingId`: 0, `maxPages`: 50 (chặn vòng lặp vô hạn nếu backend trả cursor không tiến).
3. Trong HTTP node **Lập lượt nhắc**, chọn Generic Credential Type → Header Auth → tạo credential:
   - Tên hiển thị credential tùy chọn, ví dụ SGU Tour Internal Mail.
   - **Name**: `X-Internal-Mail-Key` — phải đúng vì gateway/backend đọc header này.
   - **Value**: key nội bộ cùng MAIL_INTERNAL_API_KEY. Với helper, dán từ clipboard sau Setup -CopyKey; không dùng JWT admin, Groq key hoặc Gmail App Password làm giá trị này.
4. Chạy toàn workflow từ **Chạy thủ công / Execute workflow**. Xem output node HTTP: dryRun và thống kê eligible/created/skipped/failed, không chỉ nhìn node Cấu hình xanh. Dry run không enqueue mail; worker nghiệp vụ/SMTP khác vẫn chạy độc lập nếu đang bật.
5. Đúng kết quả thì đổi dryRun thành **Boolean false**, chạy thủ công lại. Đây là false, không phải chữ “fail”. Có created > 0 thì mới có sự kiện mới để worker gửi; created = 0 có thể đơn giản chưa có booking đến mốc hoặc event đã tồn tại.
6. Kiểm tra `mail_outbox`, log booking và hộp thư test. Để tự chạy theo giờ, bật/Publish workflow; giữ múi giờ Asia/Ho_Chi_Minh. Workflow mẫu có phân trang và gọi lại theo cursor, không phải chỉ quét batch đầu.
7. Import [n8n-error-handler.workflow.json](n8n-error-handler.workflow.json), gắn node cảnh báo (Slack/Telegram/webhook) sau **Tóm tắt lỗi**, rồi mở workflow nhắc → **Settings → Error Workflow** chọn workflow này. Lỗi hạ tầng/HTTP sẽ hiện trong Executions và workflow lỗi.

API qua gateway:

```text
POST http://localhost:8888/api/v1/booking/internal/mail/reminders/dispatch
Content-Type: application/json
X-Internal-Mail-Key: <key-noi-bo>
```

```json
{"dryRun":true,"batchSize":50,"afterBookingId":0}
```

Đây không phải endpoint cho frontend gọi bằng JWT admin. Key cần có ở cả gateway và booking; không đưa key xuống trình duyệt.

## Theo dõi kết quả

```sql
SELECT id, booking_id, event_type, status, attempt_count,
       scheduled_at, next_attempt_at, sent_at, last_error
FROM mail_outbox ORDER BY id DESC LIMIT 30;
```

Đây là truy vấn chỉ đọc để kiểm tra, không phải file SQL phải import thêm.

| Trạng thái | Cách hiểu |
|---|---|
| PENDING | Chờ tới giờ/worker |
| CLAIMED / DISPATCHING | Worker đang giữ lease/xử lý gửi |
| SENT | SMTP chấp nhận, không chứng minh thư đã vào inbox/được đọc |
| RETRY | Chắc chắn chưa mở được kết nối SMTP; chờ lịch thử lại, mặc định 10 rồi 30 phút |
| FAILED | Lỗi cấu hình/payload hoặc hết số lần thử; cần kiểm tra nguyên nhân |
| UNKNOWN | Kết quả gửi không rõ ràng, cần đối chiếu; không tự reset để gửi lại |
| SKIPPED | Ngoài hộp thư test, mốc/trạng thái đã không còn hợp lệ hoặc sự kiện không phù hợp |

Event key và lease chống lập/gửi lặp trong các trường hợp được xử lý, không bảo đảm email “exactly once” trước mọi lỗi SMTP. Không sửa trực tiếp SENT/SKIPPED/UNKNOWN về PENDING chỉ để thấy mail xuất hiện.

Hiện **chưa có màn hình quản trị mail**. Theo dõi bằng DB/log/n8n Executions; trang admin chưa có chức năng đọc outbox, gửi lại hay thống kê mail.

| Lỗi | Kiểm tra |
|---|---|
| 401 | Key thiếu/sai giữa credential, gateway và booking |
| 503 | Key chưa cấu hình/không đủ dài hoặc service chưa sẵn sàng |
| 400/405 | Body, batchSize/cursor hoặc method POST |
| 200 + `code` 1500 | Quét xong nhưng có booking lỗi (`result.failed` > 0). Workflow vẫn đi tiếp trang sau; xem log booking, không cần retry cả trang |
| 500 | Lỗi hạ tầng (DB/planner); n8n retry 3 lần rồi báo lỗi qua Error Workflow. Không dùng Never Error để che lỗi |
| Không nhận thư | Đã tới mốc chưa, created > 0 không, MAIL_ENABLED/test recipient, trạng thái outbox và spam |
| Không chạy theo giờ | Workflow đã bật/Publish, timezone, n8n và backend có online |
| Port 8081/8083/8090/8888 bị chiếm | Có bản IDE/JAR khác đang chạy; dừng đúng bản trước khi dùng helper |

## Kiểm thử

Tại booking_service: `mvn '-Dtest=*Test' test`. Test mail mock/H2 không gửi SMTP thật. `MailSmtpSmokeTest` là bài riêng có điều kiện khi chủ động cấu hình `MAIL_SMTP_SMOKE_TEST=true`; chỉ chạy khi muốn gửi một thư thật tới hộp thư đã cho phép, không coi đó là test mặc định.

Các SQL fixture trong `src/test/resources/db` chỉ được test nạp, không import vào DBtour. Database tổng không tạo mail chờ gửi hay giao dịch ngân hàng giả để làm bằng chứng.

Các yêu cầu cũ về mốc nhắc cố định hoặc import migration riêng đã được thay bằng luồng hiện tại trong source và SQL tổng. Không cần tìm lại tài liệu triển khai cũ để chạy mail trên máy mới.

[Tổng quan](../PROJECT.md) · [Backend](../../README.md) · [Database](../../data_seed/README.md).
