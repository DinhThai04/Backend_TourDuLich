# Kết quả kiểm thử nhánh lỗi API - SGU Tour backend

Tạo lúc: 2026-11-17 13:25  
Bộ test: `.runtime/api-error-test.ps1` (chỉ kiểm thử nhánh lỗi)  
Báo cáo đi kèm cho lần chạy happy-path: `docs/API_TEST_RESULTS.md`.

## 1. Mục tiêu

Kiểm thử toàn bộ các nhánh thất bại mà tám service và API gateway phơi ra, đồng thời khẳng định rằng:

- mã trạng thái HTTP khớp với hợp đồng đã tài liệu hoá;
- envelope phản hồi mang đúng `code` nghiệp vụ mong đợi (kèm `RspCode`, `error.type` hoặc `Retry-After` ở những nơi đó là một phần của hợp đồng);
- không có request bị từ chối nào trả về 5xx hoặc làm lộ stack trace;
- request bị từ chối không làm thay đổi dữ liệu (quyền sở hữu, giữ chỗ và tính idempotent được kiểm tra ở những nơi có thể quan sát).

Bộ test không bao giờ gửi request mà API được cho là phải chấp nhận, vì vậy mọi kết quả PASS bên dưới đều là một khẳng định trên đường đi âm (negative path).

## 2. Môi trường kiểm thử

| Thành phần | Địa chỉ | Ghi chú |
| --- | --- | --- |
| MySQL (instance test) | 127.0.0.1:3307, schema `dbtour_v2` | Dữ liệu dùng một lần; các ca có tính phá huỷ chỉ giới hạn trong instance này |
| auth_service | :8080 (Socket.IO :8099) | Tài khoản seed `admin@example.com`, `user1@gmail.com`, `user5@gmail.com` |
| booking_service | :8081 | |
| payment_service | :8083 | Secret test VNPay `TESTONLYSECRET0123456789`, TMN `TESTTMN01` |
| pricing_service | :8084 | |
| tour_service | :8086 | |
| file_service | :8087 | Thông tin Cloudinary là placeholder nên chỉ các nhánh policy là chạm tới được |
| search_service | :8089 | **Không chạy**: instance không có Elasticsearch/Redis. Các ca search chấp nhận 503/1503 hoặc bỏ qua |
| invoice_service | :8090 | |
| ai_service | :8091 | Model **bị tắt** (không có Groq key): chỉ các nhánh validation, scope và contract là chạm tới được |
| api_gateway | :8888 (`/api/v1`) | Các giới hạn throttle Credential/AI được nâng lên cho lần chạy chức năng |
| api_gateway có throttle | :8889 | Được khởi động bởi `.runtime/start-rate-gateway.ps1` cho các ca 429 tuỳ chọn (opt-in) |

Các service được khởi động từ các jar đã đóng gói trong thư mục `*/target` bằng `.runtime/start-stack.ps1`,
script này nạp `.local/*.properties` và nâng `AUTH_*_LIMIT` / `AI_*_LIMIT` để các nhóm chức năng không bị chạm throttle.

## 3. Cách chạy

```powershell
# 1) khởi động MySQL :3307 và mười JVM, sau đó chờ các cổng sẵn sàng
powershell -ExecutionPolicy Bypass -File .runtime\start-stack.ps1

# 2) chạy toàn bộ bộ test lỗi (mỗi nhóm một file JSON dưới .runtime/logs)
powershell -ExecutionPolicy Bypass -File .runtime\api-error-test.ps1 -Group all

# 2b) một nhóm, hoặc một mức độ, hoặc dừng ngay ở lỗi đầu tiên
powershell -ExecutionPolicy Bypass -File .runtime\api-error-test.ps1 -Group booking -Severity P0
powershell -ExecutionPolicy Bypass -File .runtime\api-error-test.ps1 -Group change -StopOnFail

# 3) các ca throttle tuỳ chọn (429 + Retry-After)
powershell -ExecutionPolicy Bypass -File .runtime\start-rate-gateway.ps1
powershell -ExecutionPolicy Bypass -File .runtime\api-error-test.ps1 -Group all -IncludeRateLimit
```

Các nhóm: `gw-auth`, `user-role`, `tour-location`, `pricing-departure`, `booking`, `change`, `payment-invoice`, `search-file-ai`.
Script trả về mã lỗi khác 0 khi có bất kỳ ca nào thất bại; kết quả cũng được ghi vào `.runtime/logs/results-err-<group>.json`.

## 4. Tóm tắt kết quả

**325 PASS, 0 FAIL, 1 SKIP** trên 326 ca (P0: 85, P1: 185, P2: 56).

| Nhóm | Số ca | PASS | FAIL | SKIP | P0 | P1 | P2 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| gw-auth | 47 | 47 | 0 | 0 | 14 | 24 | 9 |
| user-role | 51 | 51 | 0 | 0 | 15 | 32 | 4 |
| tour-location | 41 | 41 | 0 | 0 | 5 | 18 | 18 |
| pricing-departure | 35 | 35 | 0 | 0 | 10 | 25 | 0 |
| booking | 32 | 32 | 0 | 0 | 6 | 21 | 5 |
| change | 31 | 31 | 0 | 0 | 9 | 21 | 1 |
| payment-invoice | 44 | 44 | 0 | 0 | 17 | 23 | 4 |
| search-file-ai | 45 | 44 | 0 | 1 | 9 | 21 | 15 |
| **Tổng** | **326** | **325** | **0** | **1** | **85** | **185** | **56** |

Mọi khẳng định đều đạt trong lần chạy đầy đủ gần nhất (`-Group all -IncludeRateLimit`); ca duy nhất bị bỏ qua là
ca hạ tầng có tính phá huỷ được mô tả trong mục 6.

Độ phủ endpoint: cả mười module và **toàn bộ 127 mapping controller** hiện đều có ít nhất một ca âm - hoặc là một
guard thật (validation, quyền sở hữu, phân quyền, optimistic lock, chữ ký, phụ thuộc bị gián đoạn) hoặc, với các
endpoint đọc công khai không có tham số, là một ca sai phương thức/đường dẫn không công khai được edge trả 401/1401
cộng với một ca tham số xấu ở những endpoint có nhận tham số.

## 5. Phát hiện và quan sát

Không tái hiện được lỗi bảo mật nào: kiểm tra quyền sở hữu, giới hạn ghế/hold, optimistic locking, xác thực chữ ký,
tính idempotent và các cổng phân quyền đều hành xử đúng như tài liệu. Các hành vi dưới đây được ghi lại vì chúng
khác nhau giữa các service hoặc lỏng hơn so với một hợp đồng chặt chẽ. Không có hành vi nào là 5xx hay rò rỉ dữ liệu.

| ID | Hành vi | Vì sao quan trọng |
| --- | --- | --- |
| GW-008 | Một GET vào đường dẫn nội bộ dispatch mail trả 503/1503 ("chưa cấu hình") trước khi tới bước kiểm tra phương thức 405/1405. | Guard "chưa có key" chạy trước; việc ép đúng phương thức vẫn xảy ra khi đã có key. |
| GW-011 | `PUT /auth/users/login` được trả 401/1401 chứ không phải 405/1405, vì danh sách route công khai là theo từng phương thức. | Phương thức sai không bao giờ tới được handler login; edge trả lời trước. |
| AU-007 / AU-028 | Tài khoản bị khoá đăng nhập thất bại với 401/1025 giống hệt mật khẩu sai; `ACCOUNT_INACTIVE` (403/1024) chỉ xuất hiện trên đường refresh. | Đây là thiết kế chống liệt kê tài khoản có chủ đích; khoá vẫn được áp dụng, refresh token phát hành trước khi khoá bị từ chối. |
| TL-004..006 / BK-025..026 / BK-033 / CH-002 / PY-055 | Tìm kiếm tour, danh sách booking, danh sách khách hàng, danh sách ca đủ điều kiện đổi và danh sách hoá đơn của admin kẹp (clamp) `page`/`size` thay vì từ chối chúng (HTTP 200). | Các luồng tạo booking/hoá đơn lại từ chối phân trang xấu, nên API thiếu nhất quán; giá trị chỉ bị kẹp, không bao giờ dùng để mở rộng truy vấn, và danh sách khách hàng vẫn giới hạn trong phạm vi người gọi. |
| UR-024 | `GET /auth/users/999999/events` trả 200 với danh sách rỗng. | Không phân biệt được tài khoản không tồn tại với tài khoản không có sự kiện; không có dữ liệu nào bị lộ. |
| RV-004 | Tạo review bị từ chối với 403/1403 (không có booking đã trả tiền và đã hoàn thành) trước khi id tour được kiểm tra. | Điều kiện đủ điều kiện là guard mạnh hơn; id tour rác không bao giờ có thể được review. |
| SF-011 | Đường dẫn tải xuống có separator được mã hoá bị edge trả 401/1401 thay vì file-service trả 400/404. | Traversal vẫn bị chặn; chỉ là prefix công khai không còn khớp nữa. |
| SF-015 | Xoá một ảnh orphan không tồn tại trả 400/1400 thay vì 404. | Chỉ là thiếu nhất quán về mã trạng thái. |
| SF-016 | Gửi JSON tới endpoint upload multipart trả 400/1400; auth-service trả 415/1415 cho cùng lỗi đó. | Xử lý media-type thiếu nhất quán giữa các service. |
| SF-031 | `text/plain` trên một endpoint AI được trả lời bởi mặc định của Spring (không có `code`, không có `error.type`). | Envelope của AI chỉ được tạo ra khi media type đã được chấp nhận. |
| SF-050 | Handshake Socket.IO chấp nhận thiếu phiên bản `EIO` (200). | Chỉ transport không xác định (400) hoặc origin không được phép (401) mới bị từ chối. |
| SF-060 | Throttle tải file trả 429 với body rỗng và header `Retry-After`. | Throttle ở gateway trả 429 kèm envelope JSON; người gọi phải đọc header cho endpoint tải xuống. |
| SF-061 / GW-RL-01 / GW-RL-02 | Throttle Credential và AI được áp dụng ở gateway với 429, `Retry-After` và code 1429 (`error.type` là `AI_RATE_LIMITED` với AI). | `4290` là code của ai-service chứ không phải thứ edge trả về; edge giữ ngân sách cho AI. |
| SF-032 / SF-037 | Một hội thoại AI không tồn tại hoặc đã hết hạn trả **410** với code 4100 / `CONVERSATION_EXPIRED`. | Envelope AI dùng `status * 10`, nên code là 4100 chứ không phải 4040. |
| SF-039 | Một tin nhắn chat 20 kB bị edge từ chối với 400/4000 `INVALID_REQUEST` bởi guard kích thước. | `AI_INPUT_TOO_LARGE` (413) không thể chạm tới khi đứng sau giới hạn của gateway. |
| TL-027 / TL-028 | Hai route `/tour/advice/*` là công khai (không cần bearer token) và body tìm kiếm được parse chặt: field lạ trả 400/4000 `INVALID_REQUEST`, body vượt 16 kB trả 413/4130. | Bề mặt advice được thiết kế để truy cập ẩn danh; chính validation, không phải xác thực, mới là thứ giới hạn nó. |
| TL-021..023 / TL-033..036 / RV-011 / AU-030 | Một phương thức không nằm trong danh sách GET/POST công khai bị trả 401/1401 kể cả với đường dẫn có thể đọc được bằng GET. | Edge quyết định dựa trên phương thức + đường dẫn trước khi route, nên phương thức sai không bao giờ tới service. |
| SF-044 / SF-045 | `GET /ai/health` (gọi trực tiếp :8091) và `GET /ai/policies` là các kiểm tra hợp đồng, không phải nhánh lỗi: chúng xác nhận model bị tắt và danh mục policy ẩn danh. | Ghi lại để hai lần đọc AI này không bị nhầm là endpoint chưa được kiểm thử. |

### Các bất biến đã được kiểm chứng (tiêu biểu)

- **Quyền sở hữu**: đọc/xoá booking của khách hàng khác, đọc hoá đơn của tài khoản khác, mở các lựa chọn đổi của tài khoản khác và cập nhật hồ sơ của tài khoản khác đều trả 403 mà không chạm vào bản ghi (BK-024, BK-028, CH-005, CH-014, PY-003, PY-032, PY-053, UR-003, UR-015, UR-022).
- **Giới hạn ghế/hold**: booking có 41 hành khách bị từ chối 409/1409 và hold thứ sáu bị từ chối (BK-008, fixture BK-050).
- **Optimistic locking**: `expectedVersion` cũ trên một departure hoặc một lượt duyệt change trả 409/1409 (PD-030, CH-024).
- **Chữ ký**: IPN VNPay không có chữ ký, có chữ ký hỏng hoặc có số tiền không khớp đều bị từ chối với `RspCode` 97 / 97 / 04 trong khi endpoint vẫn giữ HTTP 200 (PY-020..PY-024).
- **Idempotent**: phát lại một IPN VNPay đã xác nhận trả `RspCode` 02 và quyết định lặp lại trên một change đã duyệt là no-op (PY-027, CH-023).
- **Validation**: mọi lỗi DTO đều trả 400 kèm code nghiệp vụ và không có stack trace (tất cả các nhóm).

## 6. Giới hạn độ phủ (những gì lần chạy này không chạm tới)

| Khu vực | Trạng thái | Lý do / cách xử lý |
| --- | --- | --- |
| Các nhánh thành công và validation của search_service | Phủ một phần | Service không khởi động được nếu thiếu Elasticsearch/Redis, nên `/search/**` trả 503/1503. Các ca SF-001..SF-006 chấp nhận 400/503; phần phủ 503 đồng thời đóng vai trò nhánh phụ thuộc bị gián đoạn. |
| Các nhánh thành công của model AI | Chưa phủ | Model bị tắt (không có Groq key). Chỉ các nhánh envelope, scope guard và validation được khẳng định (SF-030..SF-043). |
| Upload Cloudinary | Chưa phủ | Thông tin đăng nhập là placeholder; chỉ các nhánh của ImageUploadPolicy (SVG, quá kích thước, thiếu part) được khẳng định (SF-017..SF-019, TL-018..TL-020, LC-007). |
| Tạo thanh toán MoMo | Chỉ nhánh cấu hình | MoMo chưa được cấu hình, nên một lệnh tạo hợp lệ trả 503 và IPN trả status 99 (PY-034, PY-035). |
| Render URL return của VNPay | Chỉ nhánh lỗi | Chữ ký hỏng trả 400/1400; một lượt return thành công trên trình duyệt là happy path (PY-025). |
| Ca auth-breaker `-IncludeInfra` (SF-070) | Bỏ qua | Dừng auth_service để quan sát circuit breaker 503/1503 của gateway là việc gây gián đoạn; hãy chạy thủ công với `-IncludeInfra` trên một instance riêng. |

## 7. Ma trận ca kiểm thử

### 1. Gateway và xác thực

| ID | Mức độ | Phương thức | Đường dẫn | Mong đợi | Thực tế | Trạng thái |
| --- | --- | --- | --- | --- | --- | --- |
| AU-001 | P1 | POST | /auth/users/login | 400 / 1027 | 400 / 1027 | PASS |
| AU-002 | P1 | POST | /auth/users/login | 400 / 1001 | 400 / 1001 | PASS |
| AU-003 | P1 | POST | /auth/users/login | 400 / 1001/1009 | 400 / 1001 | PASS |
| AU-004 | P1 | POST | /auth/users/login | 400 / 1001/1027 | 400 / 1027 | PASS |
| AU-005 | P0 | POST | /auth/users/login | 401 / 1025 | 401 / 1025 | PASS |
| AU-006 | P1 | POST | /auth/users/login | 401 / 1025 | 401 / 1025 | PASS |
| AU-007 | P0 | POST | /auth/users/login | 401 / 1025 | 401 / 1025 | PASS |
| AU-008 | P1 | POST | /auth/users/register | 400 / 1021 | 400 / 1021 | PASS |
| AU-009 | P1 | POST | /auth/users/register | 400 / 1021 | 400 / 1021 | PASS |
| AU-010 | P1 | POST | /auth/users/register | 400 / 1001 | 400 / 1001 | PASS |
| AU-011 | P1 | POST | /auth/users/register | 400 / 1009/1001 | 400 / 1001 | PASS |
| AU-012 | P1 | POST | /auth/users/register | 400 / 1001 | 400 / 1001 | PASS |
| AU-013 | P1 | POST | /auth/users/register | 400 / 1001 | 400 / 1001 | PASS |
| AU-014 | P1 | POST | /auth/users/register | 400 / 1001 | 400 / 1001 | PASS |
| AU-015 | P1 | POST | /auth/users/register | 400 / 1001 | 400 / 1001 | PASS |
| AU-016 | P0 | POST | /auth/users/refresh | 401 / 1010/1011/1006 | 401 / 1010 | PASS |
| AU-017 | P1 | POST | /auth/users/refresh | 401 / 1010/1011/1006/1001 | 401 / 1010 | PASS |
| AU-018 | P1 | POST | /auth/users/refresh | 401 / 1010/1011/1006 | 401 / 1010 | PASS |
| AU-019 | P0 | POST | /auth/users/Change-password | 401 / 1401 | 401 / 1401 | PASS |
| AU-020 | P0 | POST | /auth/users/Change-password | 400 / 1005 | 400 / 1005 | PASS |
| AU-021 | P1 | POST | /auth/users/Change-password | 400 / 1012 | 400 / 1012 | PASS |
| AU-022 | P1 | POST | /auth/users/Change-password | 400 / 1001 | 400 / 1001 | PASS |
| AU-023 | P1 | POST | http://localhost:8080/auth/users/introspect | 200 | 200 / 1000 | PASS |
| AU-024 | P1 | POST | /auth/users/logout | 200 | 200 / 1000 | PASS |
| AU-025 | P0 | GET | /auth/users/ | 401 / 1401/1006 | 401 / 1006 | PASS |
| AU-026 | P0 | GET | /auth/users/ | 401 / 1401/1006 | 401 / 1006 | PASS |
| AU-027 | P2 | POST | /auth/users/login | 200 | 200 | PASS |
| AU-028 | P0 | POST | /auth/users/refresh | 401/403 / 1024/1006/1010/1011 | 401 / 1006 | PASS |
| AU-029 | P1 | POST | /auth/users/google | 400 / 1400 | 400 / 1400 | PASS |
| AU-030 | P2 | GET | /auth/users/google | 401 / 1401 | 401 / 1401 | PASS |
| GW-001 | P0 | GET | /booking/book/all | 401 / 1401 | 401 / 1401 | PASS |
| GW-002 | P0 | GET | /booking/book/all | 401 / 1401 | 401 / 1401 | PASS |
| GW-003 | P0 | GET | /booking/book/all | 401 / 1401 | 401 / 1401 | PASS |
| GW-004 | P0 | GET | /booking/book/all | 401 / 1401 | 401 / 1401 | PASS |
| GW-005 | P0 | GET | /booking/book/all | 401 / 1401 | 401 / 1401 | PASS |
| GW-006 | P1 | GET | /booking/book/all | 401 / 1401 | 401 / 1401 | PASS |
| GW-007 | P0 | POST | /auth/users/introspect | 403 / 1403 | 403 / 1403 | PASS |
| GW-008 | P2 | GET | /booking/internal/mail/reminders/dispatch | 405/503 / 1405/1503 | 503 / 1503 | PASS |
| GW-009 | P1 | POST | /booking/internal/mail/reminders/dispatch | 401/503 / 1401/1503 | 503 / 1503 | PASS |
| GW-010 | P2 | GET | /does/not/exist | 404 / 1404 | 404 / 1404 | PASS |
| GW-011 | P2 | PUT | /auth/users/login | 401/405 / 1401/1405 | 401 / 1401 | PASS |
| GW-012 | P2 | POST | /auth/users/login | 415 / 1415 | 415 / 1415 | PASS |
| GW-013 | P1 | POST | /auth/users/login | 400 / 1400 | 400 / 1400 | PASS |
| GW-014 | P1 | OPTIONS | /auth/users/login | 403 | 403 | PASS |
| GW-015 | P2 | OPTIONS | /auth/users/login | 200 | 200 | PASS |
| GW-RL-01 | P2 | POST | /auth/users/login | 429 / 1429 | 429 / 1429 | PASS |
| GW-RL-02 | P2 | POST | /auth/users/register | 429 / 1429 | 429 / 1429 | PASS |

### 2. Người dùng, vai trò, quyền và review

| ID | Mức độ | Phương thức | Đường dẫn | Mong đợi | Thực tế | Trạng thái |
| --- | --- | --- | --- | --- | --- | --- |
| PM-001 | P1 | POST | /auth/permissions | 400 / 1400/1001 | 400 / 1400 | PASS |
| PM-002 | P1 | POST | /auth/permissions | 400 / 1400/1001 | 400 / 1001 | PASS |
| PM-003 | P1 | POST | /auth/permissions | 400 / 1001/1400 | 400 / 1001 | PASS |
| PM-004 | P1 | GET | /auth/permissions/999999 | 404 / 1014 | 404 / 1014 | PASS |
| PM-005 | P1 | PUT | /auth/permissions | 400 / 1400/1001 | 400 / 1400 | PASS |
| PM-006 | P0 | GET | /auth/permissions/list | 403 / 1007 | 403 / 1007 | PASS |
| PM-007 | P0 | DELETE | /auth/permissions/1 | 403 / 1007 | 403 / 1007 | PASS |
| RL-001 | P1 | POST | /auth/roles | 400 / 1400 | 400 / 1400 | PASS |
| RL-002 | P1 | POST | /auth/roles | 400 / 1017/1015/1029 | 400 / 1029 | PASS |
| RL-003 | P1 | POST | /auth/roles | 400 / 1001 | 400 / 1001 | PASS |
| RL-004 | P1 | PUT | /auth/roles | 400 / 1400 | 400 / 1400 | PASS |
| RL-005 | P1 | GET | /auth/roles/999999 | 404 / 1013 | 404 / 1013 | PASS |
| RL-006 | P1 | DELETE | /auth/roles/999999 | 404 / 1013 | 404 / 1013 | PASS |
| RL-007 | P0 | GET | /auth/roles | 403 / 1007 | 403 / 1007 | PASS |
| RL-008 | P0 | POST | /auth/roles | 403 / 1007 | 403 / 1007 | PASS |
| RV-001 | P1 | GET | /auth/reviews/eligibility | 400 / 1400 | 400 / 1400 | PASS |
| RV-002 | P1 | GET | /auth/reviews/eligibility?tourId=abc | 400 / 1400 | 400 / 1400 | PASS |
| RV-003 | P0 | POST | /auth/reviews | 401 / 1401 | 401 / 1401 | PASS |
| RV-004 | P1 | POST | /auth/reviews | 403 / 1403 | 403 / 1403 | PASS |
| RV-005 | P1 | POST | /auth/reviews | 400 / 1001 | 400 / 1001 | PASS |
| RV-006 | P1 | POST | /auth/reviews | 400 / 1001 | 400 / 1001 | PASS |
| RV-007 | P1 | POST | /auth/reviews | 400 / 1001 | 400 / 1001 | PASS |
| RV-008 | P1 | POST | /auth/reviews | 400 / 1001 | 400 / 1001 | PASS |
| RV-009 | P2 | GET | /auth/reviews/high-rating?size=abc | 400 / 1400 | 400 / 1400 | PASS |
| RV-010 | P0 | GET | /auth/reviews/list | 403 / 1007 | 403 / 1007 | PASS |
| RV-011 | P2 | GET | /auth/reviews/tour/abc | 401 / 1401 | 401 / 1401 | PASS |
| RV-012 | P2 | GET | /auth/reviews/tour/999999 | 200 | 200 / 1000 | PASS |
| UR-001 | P1 | GET | /auth/users/999999 | 404 / 1028 | 404 / 1028 | PASS |
| UR-002 | P1 | GET | /auth/users/abc | 400 / 1400 | 400 / 1400 | PASS |
| UR-003 | P0 | GET | /auth/users/7 | 403 / 1007 | 403 / 1007 | PASS |
| UR-004 | P0 | GET | /auth/users/list | 403 / 1007 | 403 / 1007 | PASS |
| UR-005 | P0 | GET | /auth/users/display-names?ids=3 | 403 / 1007 | 403 / 1007 | PASS |
| UR-006 | P1 | GET | /auth/users/display-names?ids=1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32,33,34,35,36,37,38,39,40,41,42,43,44,45,46,47,48,49,50,51,52,53,54,55,56,57,58,59,60,61,62,63,64,65,66,67,68,69,70,71,72,73,74,75,76,77,78,79,80,81,82,83,84,85,86,87,88,89,90,91,92,93,94,95,96,97,98,99,100,101 | 400 / 1400 | 400 / 1400 | PASS |
| UR-007 | P1 | GET | /auth/users/display-names?ids=abc | 400 / 1400 | 400 / 1400 | PASS |
| UR-008 | P1 | GET | /auth/users/display-names | 400 / 1400 | 400 / 1400 | PASS |
| UR-009 | P0 | POST | /auth/users | 403 / 1007 | 403 / 1007 | PASS |
| UR-010 | P1 | POST | /auth/users | 400 / 1021 | 400 / 1021 | PASS |
| UR-011 | P1 | POST | /auth/users | 404 / 1013 | 404 / 1013 | PASS |
| UR-012 | P1 | POST | /auth/users | 400 / 1001 | 400 / 1001 | PASS |
| UR-013 | P1 | POST | /auth/users/UpdateUser/999999 | 404 / 1026 | 404 / 1026 | PASS |
| UR-014 | P1 | POST | /auth/users/UpdateUser/abc | 400 / 1400 | 400 / 1400 | PASS |
| UR-015 | P0 | POST | /auth/users/UpdateUser/7 | 403 / 1007 | 403 / 1007 | PASS |
| UR-016 | P0 | PUT | /auth/users/7/status | 403 / 1007 | 403 / 1007 | PASS |
| UR-017 | P1 | PUT | /auth/users/7/status | 400 / 1400 | 400 / 1400 | PASS |
| UR-018 | P1 | PUT | /auth/users/7/status | 400 / 1001/1400 | 400 / 1001 | PASS |
| UR-019 | P0 | PUT | /auth/users/1/status | 403 / 1403 | 403 / 1403 | PASS |
| UR-020 | P1 | POST | /auth/users/7/archive | 409 / 1409 | 409 / 1409 | PASS |
| UR-021 | P1 | DELETE | /auth/users/999999?reason=negative%20test | 404 / 1404/1026 | 404 / 1404 | PASS |
| UR-022 | P0 | DELETE | /auth/users/7 | 403 / 1007 | 403 / 1007 | PASS |
| UR-023 | P0 | GET | /auth/users/7/events | 403 / 1007 | 403 / 1007 | PASS |
| UR-024 | P2 | GET | /auth/users/999999/events | 200 | 200 / 1000 | PASS |

### 3. Tour, địa điểm và upload

| ID | Mức độ | Phương thức | Đường dẫn | Mong đợi | Thực tế | Trạng thái |
| --- | --- | --- | --- | --- | --- | --- |
| LC-001 | P0 | POST | /tour/locations | 403 | 403 | PASS |
| LC-002 | P2 | POST | /tour/locations | 415 / 1415 | 415 / 1415 | PASS |
| LC-003 | P1 | POST | /tour/locations | 400 / 1001/1400 | 400 / 1001 | PASS |
| LC-004 | P1 | POST | /tour/locations | 400 / 1400 | 400 / 1400 | PASS |
| LC-005 | P1 | PUT | /tour/locations/999999 | 404 / 1016/1404 | 404 / 1404 | PASS |
| LC-006 | P0 | PUT | /tour/locations/1 | 403 | 403 | PASS |
| LC-007 | P2 | POST | /tour/tours/17/images | 400 / 1400/1413 | 400 / 1400 | PASS |
| TL-001 | P1 | GET | /tour/tours/0 | 404 / 1013/1404 | 404 / 1013 | PASS |
| TL-002 | P1 | GET | /tour/tours/999999 | 404 / 1013/1404 | 404 / 1013 | PASS |
| TL-003 | P1 | GET | /tour/tours/abc | 401 / 1401 | 401 / 1401 | PASS |
| TL-004 | P2 | GET | /tour/tours/search?page=-1 | 200 | 200 / 1000 | PASS |
| TL-005 | P2 | GET | /tour/tours/search?size=0 | 200 | 200 / 1000 | PASS |
| TL-006 | P2 | GET | /tour/tours/search?size=1000 | 200 | 200 / 1000 | PASS |
| TL-007 | P1 | GET | /tour/tours/abc | 400 / 1400 | 400 / 1400 | PASS |
| TL-010 | P0 | POST | /tour/tours | 401 / 1401 | 401 / 1401 | PASS |
| TL-011 | P0 | POST | /tour/tours | 403 | 403 | PASS |
| TL-012 | P1 | POST | /tour/tours | 400 / 1001/1400 | 400 / 1001 | PASS |
| TL-013 | P1 | POST | /tour/tours | 400 / 1001/1400 | 400 / 1001 | PASS |
| TL-014 | P1 | PUT | /tour/tours/999999 | 404 / 1013/1404 | 404 / 1013 | PASS |
| TL-015 | P1 | PUT | /tour/tours/abc | 400 / 1400 | 400 / 1400 | PASS |
| TL-016 | P1 | DELETE | /tour/tours/999999 | 404 / 1013/1404 | 404 / 1013 | PASS |
| TL-017 | P0 | DELETE | /tour/tours/17 | 403 | 403 | PASS |
| TL-018 | P2 | POST | /tour/tours/999999/images | 404 / 1013/1404 | 404 / 1013 | PASS |
| TL-019 | P2 | POST | /tour/tours/17/images | 415 / 1415 | 415 / 1415 | PASS |
| TL-020 | P2 | POST | /tour/tours/17/images | 400 / 1400 | 400 / 1400 | PASS |
| TL-021 | P2 | POST | /tour/tours/list | 401 / 1401 | 401 / 1401 | PASS |
| TL-022 | P2 | DELETE | /tour/tours/random-3 | 401 / 1401 | 401 / 1401 | PASS |
| TL-023 | P2 | POST | /tour/vehicles/list | 401 / 1401 | 401 / 1401 | PASS |
| TL-024 | P1 | GET | /tour/itineraries/999999 | 404 / 1015/1404 | 404 / 1015 | PASS |
| TL-025 | P1 | GET | /tour/itineraries/abc | 400 / 1400 | 400 / 1400 | PASS |
| TL-026 | P2 | GET | /tour/itineraries/tour/999999 | 200 | 200 / 1000 | PASS |
| TL-027 | P1 | POST | /tour/advice/search | 400 / 4000 | 400 / 4000 | PASS |
| TL-028 | P2 | GET | /tour/advice/locations | 200 | 200 / 1000 | PASS |
| TL-029 | P1 | POST | /tour/advice/search | 400 / 4000 | 400 / 4000 | PASS |
| TL-030 | P1 | POST | /tour/advice/search | 413 / 4130 | 413 / 4130 | PASS |
| TL-031 | P1 | GET | /tour/locations/departures/search | 400 / 1400 | 400 / 1400 | PASS |
| TL-032 | P2 | GET | /tour/locations/destinations/search?keyword= | 200 | 200 / 1000 | PASS |
| TL-033 | P2 | POST | /tour/locations/all | 401 / 1401 | 401 / 1401 | PASS |
| TL-034 | P2 | POST | /tour/locations/departures | 401 / 1401 | 401 / 1401 | PASS |
| TL-035 | P2 | POST | /tour/locations/destinations | 401 / 1401 | 401 / 1401 | PASS |
| TL-036 | P2 | POST | /tour/locations/destinations/attractive | 401 / 1401 | 401 / 1401 | PASS |

### 4. Giá, phương tiện, lịch trình và khởi hành

| ID | Mức độ | Phương thức | Đường dẫn | Mong đợi | Thực tế | Trạng thái |
| --- | --- | --- | --- | --- | --- | --- |
| PD-001 | P0 | POST | /pricing/prices | 401 / 1401 | 401 / 1401 | PASS |
| PD-002 | P0 | POST | /pricing/prices | 403 | 403 | PASS |
| PD-003 | P1 | POST | /pricing/prices | 400 / 1400 | 400 / 1400 | PASS |
| PD-004 | P1 | PUT | /pricing/prices/999999 | 404 / 1404/1013 | 404 / 1013 | PASS |
| PD-005 | P1 | GET | /pricing/prices/999999 | 404 / 1013/1404 | 404 / 1013 | PASS |
| PD-006 | P0 | GET | /pricing/prices | 403 | 403 | PASS |
| PD-007 | P1 | GET | /pricing/prices/abc | 401 / 1401 | 401 / 1401 | PASS |
| PD-008 | P1 | POST | /pricing/prices/batch | 400 / 1001 | 400 / 1001 | PASS |
| PD-009 | P1 | POST | /pricing/prices/batch | 400 / 1001 | 400 / 1001 | PASS |
| PD-010 | P1 | POST | /pricing/prices/batch | 400 / 1001 | 400 / 1001 | PASS |
| PD-011 | P1 | POST | /pricing/prices/batch | 400 / 1001 | 400 / 1001 | PASS |
| PD-020 | P1 | GET | /tour/tour-departures/999999 | 404 / 1014/1404 | 404 / 1014 | PASS |
| PD-021 | P1 | GET | /tour/tour-departures/abc | 401 / 1401 | 401 / 1401 | PASS |
| PD-022 | P1 | GET | /tour/tour-departures/list | 401 / 1401 | 401 / 1401 | PASS |
| PD-023 | P0 | POST | /tour/tour-departures | 401 / 1401 | 401 / 1401 | PASS |
| PD-024 | P0 | POST | /tour/tour-departures | 403 | 403 | PASS |
| PD-025 | P1 | POST | /tour/tour-departures | 400 / 1400 | 400 / 1400 | PASS |
| PD-026 | P1 | POST | /tour/tour-departures | 400 / 1400 | 400 / 1400 | PASS |
| PD-027 | P1 | POST | /tour/tour-departures | 400 / 1400 | 400 / 1400 | PASS |
| PD-028 | P1 | POST | /tour/tour-departures | 404 / 1013/1404 | 404 / 1013 | PASS |
| PD-029 | P1 | PUT | /tour/tour-departures/999999 | 404 / 1014/1404 | 404 / 1404 | PASS |
| PD-030 | P0 | PUT | /tour/tour-departures/308 | 409 / 1409 | 409 / 1409 | PASS |
| PD-031 | P0 | GET | /tour/tour-departures/tour/1/all | 403 | 403 | PASS |
| PD-032 | P1 | GET | /tour/tour-departures/308/capacity | 401 / 1401 | 401 / 1401 | PASS |
| PD-040 | P0 | POST | /tour/itineraries | 401 / 1401 | 401 / 1401 | PASS |
| PD-041 | P0 | POST | /tour/itineraries | 403 | 403 | PASS |
| PD-042 | P1 | POST | /tour/itineraries | 400 / 1001 | 400 / 1001 | PASS |
| PD-043 | P1 | POST | /tour/itineraries | 400 / 1001 | 400 / 1001 | PASS |
| PD-044 | P1 | POST | /tour/itineraries | 400/404 / 1013/1400/1404 | 404 / 1013 | PASS |
| PD-045 | P1 | PUT | /tour/itineraries/999999 | 404 / 1015/1404 | 404 / 1015 | PASS |
| PD-046 | P1 | DELETE | /tour/itineraries/999999 | 404 / 1015/1404 | 404 / 1015 | PASS |
| PD-047 | P1 | GET | /tour/itineraries/all | 401 / 1401 | 401 / 1401 | PASS |
| PD-050 | P1 | POST | /tour/vehicles | 400 / 1001 | 400 / 1001 | PASS |
| PD-051 | P0 | POST | /tour/vehicles | 403 | 403 | PASS |
| PD-052 | P1 | POST | /tour/vehicles | 409 / 1409/1001 | 409 / 1409 | PASS |

### 5. Booking, khách hàng và advice

| ID | Mức độ | Phương thức | Đường dẫn | Mong đợi | Thực tế | Trạng thái |
| --- | --- | --- | --- | --- | --- | --- |
| BK-001 | P0 | POST | /booking/book/create | 401 / 1401 | 401 / 1401 | PASS |
| BK-002 | P0 | POST | /booking/book/create | 403 / 1403 | 403 / 1403 | PASS |
| BK-003 | P1 | POST | /booking/book/create | 400 / 1001 | 400 / 1001 | PASS |
| BK-004 | P1 | POST | /booking/book/create | 400 / 1001 | 400 / 1001 | PASS |
| BK-005 | P1 | POST | /booking/book/create | 400 / 1001 | 400 / 1001 | PASS |
| BK-006 | P1 | POST | /booking/book/create | 404 / 1404 | 404 / 1404 | PASS |
| BK-007 | P1 | POST | /booking/book/create | 400 / 1400 | 400 / 1400 | PASS |
| BK-008 | P1 | POST | /booking/book/create | 409 / 1409 | 409 / 1409 | PASS |
| BK-009 | P1 | POST | /booking/book/create | 400 / 1400 | 400 / 1400 | PASS |
| BK-010 | P1 | POST | /booking/book/create | 400 / 1400 | 400 / 1400 | PASS |
| BK-011 | P1 | POST | /booking/book/create | 400 / 1400 | 400 / 1400 | PASS |
| BK-012 | P1 | POST | /booking/book/create | 400 / 1400 | 400 / 1400 | PASS |
| BK-020 | P1 | GET | /booking/book/0 | 404 / 1012 | 404 / 1012 | PASS |
| BK-021 | P1 | GET | /booking/book/-1 | 404 / 1012 | 404 / 1012 | PASS |
| BK-022 | P1 | GET | /booking/book/abc | 400 / 1400 | 400 / 1400 | PASS |
| BK-023 | P1 | GET | /booking/book/999999 | 404 / 1404/1012 | 404 / 1404 | PASS |
| BK-024 | P0 | GET | /booking/book/27 | 403 / 1403 | 403 / 1403 | PASS |
| BK-025 | P2 | GET | /booking/book/all?page=-1&size=20&mine=true | 200 | 200 / 1000 | PASS |
| BK-026 | P2 | GET | /booking/book/all?page=0&size=0&mine=true | 200 | 200 / 1000 | PASS |
| BK-027 | P1 | DELETE | /booking/book/999999 | 404 / 1404/1012 | 404 / 1404 | PASS |
| BK-028 | P0 | DELETE | /booking/book/27 | 403 / 1403 | 403 / 1403 | PASS |
| BK-030 | P1 | GET | /booking/customer/booking/999999 | 404 / 1404 | 404 / 1404 | PASS |
| BK-031 | P1 | GET | /booking/customer/999999 | 404 / 1404 | 404 / 1404 | PASS |
| BK-032 | P1 | POST | /booking/customer/create | 409 / 1409 | 409 / 1409 | PASS |
| BK-033 | P2 | GET | /booking/customer/all?page=-1&size=0 | 200 | 200 / 1000 | PASS |
| BK-040 | P0 | GET | /booking/advice/mine | 401 / 1401 | 401 / 1401 | PASS |
| BK-041 | P1 | GET | /booking/advice/mine?bookingId=abc | 400 / 1400 | 400 / 1400 | PASS |
| BK-042 | P0 | GET | /booking/advice/mine?bookingId=27 | 200 | 200 / 1000 | PASS |
| BK-043 | P1 | GET | /booking/advice/review-eligibility | 400 / 1400/1001 | 400 / 1400 | PASS |
| BK-044 | P1 | GET | /booking/advice/review-eligibility?tourId=abc | 400 / 1400/1001 | 400 / 1400 | PASS |
| BK-045 | P2 | GET | /booking/advice/review-eligibility?tourId=0 | 200/400 | 400 / 1400 | PASS |
| BK-050 | P2 | POST | /booking/book/create | 200 | 200 | PASS |

### 6. Yêu cầu đổi và sự cố shop

| ID | Mức độ | Phương thức | Đường dẫn | Mong đợi | Thực tế | Trạng thái |
| --- | --- | --- | --- | --- | --- | --- |
| CH-001 | P0 | GET | /booking/changes/mine | 401 / 1401 | 401 / 1401 | PASS |
| CH-002 | P2 | GET | /booking/changes/eligible?page=-1 | 200 | 200 / 1000 | PASS |
| CH-003 | P1 | GET | /booking/changes/options | 400 / 1400 | 400 / 1400 | PASS |
| CH-004 | P1 | GET | /booking/changes/options?bookingId=abc | 400 / 1400 | 400 / 1400 | PASS |
| CH-005 | P0 | GET | /booking/changes/options?bookingId=27 | 403 / 1403 | 403 / 1403 | PASS |
| CH-006 | P1 | GET | /booking/changes/options?bookingId=999999 | 404 / 1404 | 404 / 1404 | PASS |
| CH-010 | P0 | POST | /booking/changes/customer | 401 / 1401 | 401 / 1401 | PASS |
| CH-011 | P1 | POST | /booking/changes/customer | 400 / 1001 | 400 / 1001 | PASS |
| CH-012 | P1 | POST | /booking/changes/customer | 400 / 1001 | 400 / 1001 | PASS |
| CH-013 | P1 | POST | /booking/changes/customer | 400 / 1001 | 400 / 1001 | PASS |
| CH-014 | P0 | POST | /booking/changes/customer | 403 / 1403 | 403 / 1403 | PASS |
| CH-015 | P1 | POST | /booking/changes/customer | 404 / 1404 | 404 / 1404 | PASS |
| CH-020 | P0 | GET | /booking/changes/admin | 403 / 1403/1007 | 403 / 1403 | PASS |
| CH-021 | P1 | GET | /booking/changes/admin?tourId=abc | 400 / 1400 | 400 / 1400 | PASS |
| CH-022 | P1 | POST | /booking/changes/999999/review | 404 / 1404 | 404 / 1404 | PASS |
| CH-023 | P1 | POST | /booking/changes/1/review | 409 / 1409 | 409 / 1409 | PASS |
| CH-024 | P0 | POST | /booking/changes/1/review | 409 / 1409 | 409 / 1409 | PASS |
| CH-025 | P1 | POST | /booking/changes/999999/respond | 404 / 1404 | 404 / 1404 | PASS |
| CH-026 | P1 | POST | /booking/changes/999999/refund | 404 / 1404 | 404 / 1404 | PASS |
| CH-027 | P0 | POST | /booking/changes/1/refund | 403 / 1403/1007 | 403 / 1403 | PASS |
| CH-028 | P1 | POST | /booking/changes/shop/999999/activate | 404 / 1404 | 404 / 1404 | PASS |
| CH-029 | P0 | POST | /booking/changes/shop/1/activate | 403 / 1403/1007 | 403 / 1403 | PASS |
| CH-030 | P0 | POST | /booking/changes/shop/preview | 403 / 1403/1007 | 403 / 1403 | PASS |
| CH-031 | P1 | POST | /booking/changes/shop/preview | 400 / 1001 | 400 / 1001 | PASS |
| CH-032 | P1 | POST | /booking/changes/shop/preview | 400 / 1001 | 400 / 1001 | PASS |
| CH-033 | P1 | POST | /booking/changes/shop/preview | 400 / 1001 | 400 / 1001 | PASS |
| CH-034 | P1 | POST | /booking/changes/shop/preview | 409 / 1409 | 409 / 1409 | PASS |
| CH-035 | P1 | POST | /booking/changes/shop/preview | 409 / 1409 | 409 / 1409 | PASS |
| CH-036 | P1 | POST | /booking/changes/shop/preview | 400 / 1001 | 400 / 1001 | PASS |
| CH-037 | P1 | POST | /booking/changes/shop | 400 / 1001 | 400 / 1001 | PASS |
| CH-038 | P1 | POST | /booking/changes/shop | 404 / 1404 | 404 / 1404 | PASS |

### 7. Thanh toán, đối soát và hoá đơn

| ID | Mức độ | Phương thức | Đường dẫn | Mong đợi | Thực tế | Trạng thái |
| --- | --- | --- | --- | --- | --- | --- |
| PY-001 | P0 | GET | /payment/vnpay/bookings/22/quote | 401 / 1401 | 401 / 1401 | PASS |
| PY-002 | P1 | GET | /payment/vnpay/bookings/999999/quote | 404 / 1404 | 404 / 1404 | PASS |
| PY-003 | P0 | GET | /payment/vnpay/bookings/27/quote | 403 / 1403 | 403 / 1403 | PASS |
| PY-004 | P1 | GET | /payment/vnpay/bookings/abc/quote | 400 / 1400 | 400 / 1400 | PASS |
| PY-010 | P0 | POST | /payment/vnpay/bookings | 401 / 1401 | 401 / 1401 | PASS |
| PY-011 | P1 | POST | /payment/vnpay/bookings | 400 | 400 | PASS |
| PY-012 | P1 | POST | /payment/vnpay/bookings | 400 | 400 | PASS |
| PY-013 | P1 | POST | /payment/vnpay/bookings | 404 | 404 | PASS |
| PY-014 | P0 | POST | /payment/vnpay/bookings | 403 | 403 | PASS |
| PY-015 | P0 | POST | /payment/vnpay/bookings | 400 | 400 | PASS |
| PY-020 | P0 | GET | /payment/vnpay/ipn | 200 / 97 | 200 / 97 | PASS |
| PY-021 | P0 | GET | /payment/vnpay/ipn?vnp_Amount=10000000&vnp_BankCode=NCB&vnp_OrderInfo=Negative+test+order&vnp_PayDate=20261117132517&vnp_ResponseCode=00&vnp_TmnCode=TESTTMN01&vnp_TransactionNo=14000000&vnp_TransactionStatus=00&vnp_TxnRef=UNKNOWNREF&vnp_SecureHash=91bb5e9452513b4f9239575d4e6e9f574bbe34fb6884a02bc982b5029e3c46509972e5870604fed6786b948aee8a49b94f805a1ddb3f0b3517ce34c8deadbeef | 200 / 97 | 200 / 97 | PASS |
| PY-022 | P1 | GET | /payment/vnpay/ipn?vnp_Amount=10000000&vnp_BankCode=NCB&vnp_OrderInfo=Negative+test+order&vnp_PayDate=20261117132517&vnp_ResponseCode=00&vnp_TmnCode=TESTTMN01&vnp_TransactionNo=14000000&vnp_TransactionStatus=00&vnp_TxnRef=NOPE-2026-0001&vnp_SecureHash=35fc88ce044847d46497bf03807c2fae80257af954e67135b3102df2e04cf7869f80dd69efade4ed10498e4d5c1d40b0998c4011411bbff7b543e027dde0bba6 | 200 / 1 | 200 / 01 | PASS |
| PY-023 | P0 | GET | /payment/vnpay/ipn?vnp_Amount=849100000&vnp_BankCode=NCB&vnp_OrderInfo=Negative+test+order&vnp_PayDate=20261117132517&vnp_ResponseCode=00&vnp_TmnCode=TESTTMN01&vnp_TransactionNo=14000000&vnp_TransactionStatus=00&vnp_TxnRef=51029da30e0f4ee1b15289eab8d2fccd&vnp_SecureHash=ddd08efbfb12f3564df2ce92511b6e477bff43754564e209798ed0537e51d1c81c94b2e0379910600b76561b1dd0bfd31f405cb0ae688f073df3de38286f6b10 | 200 / 4 | 200 / 04 | PASS |
| PY-024 | P1 | GET | /payment/vnpay/ipn?vnp_Amount=0&vnp_BankCode=NCB&vnp_OrderInfo=Negative+test+order&vnp_PayDate=20261117132517&vnp_ResponseCode=00&vnp_TmnCode=TESTTMN01&vnp_TransactionNo=14000000&vnp_TransactionStatus=00&vnp_TxnRef=51029da30e0f4ee1b15289eab8d2fccd&vnp_SecureHash=9dd56bfd8745930d9fa1131712f970045312b95e3cf0038fa51f97738a4e6788762ae508e17e6910bb02e6c951e63889ac7f9dd5d173d2f7878fbb427768d9a8 | 200 / 4/99 | 200 / 04 | PASS |
| PY-025 | P1 | GET | /payment/vnpay/bookings/return?vnp_Amount=10000000&vnp_BankCode=NCB&vnp_OrderInfo=Negative+test+order&vnp_PayDate=20261117132517&vnp_ResponseCode=00&vnp_TmnCode=TESTTMN01&vnp_TransactionNo=14000000&vnp_TransactionStatus=00&vnp_TxnRef=UNKNOWNREF&vnp_SecureHash=91bb5e9452513b4f9239575d4e6e9f574bbe34fb6884a02bc982b5029e3c46509972e5870604fed6786b948aee8a49b94f805a1ddb3f0b3517ce34c8deadbeef | 400 / 1400 | 400 / 1400 | PASS |
| PY-026 | P2 | GET | /payment/vnpay/ipn | 200 / 00 | 200 / 00 | PASS |
| PY-027 | P2 | GET | /payment/vnpay/ipn | 200 / 02 | 200 / 02 | PASS |
| PY-030 | P0 | GET | /payment/momo/bookings/22/quote | 401 / 1401 | 401 / 1401 | PASS |
| PY-031 | P1 | GET | /payment/momo/bookings/999999/quote | 404 / 1404 | 404 / 1404 | PASS |
| PY-032 | P0 | GET | /payment/momo/bookings/27/quote | 403 / 1403 | 403 / 1403 | PASS |
| PY-033 | P1 | POST | /payment/momo/bookings | 400 | 400 / 1001 | PASS |
| PY-034 | P1 | POST | /payment/momo/bookings | 503 | 503 | PASS |
| PY-035 | P1 | POST | /payment/momo/ipn | 200 | 200 | PASS |
| PY-036 | P1 | GET | /payment/momo/bookings/return?foo=bar | 400 / 1400 | 400 / 1400 | PASS |
| PY-040 | P0 | GET | /payment/reconciliation | 401 / 1401 | 401 / 1401 | PASS |
| PY-041 | P0 | GET | /payment/reconciliation | 403 / 1403 | 403 / 1403 | PASS |
| PY-042 | P1 | GET | /payment/reconciliation?page=-1 | 400 / 1400 | 400 / 1400 | PASS |
| PY-043 | P1 | GET | /payment/reconciliation?page=100001 | 400 / 1400 | 400 / 1400 | PASS |
| PY-044 | P1 | GET | /payment/reconciliation?view=BAD | 400 / 1400 | 400 / 1400 | PASS |
| PY-045 | P2 | GET | /payment/reconciliation/NOPE-2026-0001/history | 200 | 200 / 1000 | PASS |
| PY-046 | P1 | POST | /payment/reconciliation/NOPE-2026-0001/resolve | 404 / 1404 | 404 / 1404 | PASS |
| PY-047 | P0 | POST | /payment/reconciliation/NOPE-2026-0001/resolve | 403 / 1403 | 403 / 1403 | PASS |
| PY-048 | P0 | POST | /payment/reconciliation/NOPE-2026-0001/resolve | 401 / 1401 | 401 / 1401 | PASS |
| PY-049 | P1 | POST | /payment/reconciliation/NOPE-2026-0001/resolve | 400/409 / 1400/1404/1409 | 400 / 1400 | PASS |
| PY-050 | P1 | GET | /invoice/direct/999999 | 404 / 1404 | 404 / 1404 | PASS |
| PY-051 | P1 | GET | /invoice/direct/abc | 400 / 1400 | 400 / 1400 | PASS |
| PY-052 | P1 | GET | /invoice/direct/booking/999999 | 404 / 1404 | 404 / 1404 | PASS |
| PY-053 | P0 | GET | /invoice/direct/19 | 403 / 1007/1403 | 403 / 1007 | PASS |
| PY-054 | P0 | GET | /invoice/direct/all | 200 | 200 / 1000 | PASS |
| PY-055 | P2 | GET | /invoice/direct/all?page=-1 | 200 | 200 / 1000 | PASS |
| PY-056 | P0 | POST | /invoice/direct/create | 403 / 1007/1403 | 403 / 1007 | PASS |
| PY-057 | P1 | POST | /invoice/direct/create | 400 / 1001 | 400 / 1001 | PASS |
| PY-058 | P1 | POST | /invoice/direct/create | 404 / 1014/1404/1012 | 404 / 1014 | PASS |

### 8. Search, file, AI, Socket.IO và các vấn đề xuyên suốt

| ID | Mức độ | Phương thức | Đường dẫn | Mong đợi | Thực tế | Trạng thái |
| --- | --- | --- | --- | --- | --- | --- |
| SF-001 | P1 | GET | /search/tours?page=-1 | 400/503 / 1400/1503 | 503 / 1503 | PASS |
| SF-002 | P1 | GET | /search/tours?size=0 | 400/503 / 1400/1503 | 503 / 1503 | PASS |
| SF-003 | P1 | GET | /search/tours?size=1000 | 400/503 / 1400/1503 | 503 / 1503 | PASS |
| SF-004 | P2 | GET | /search/tours/search?basePrice=abc | 400/503 / 1400/1503 | 503 / 1503 | PASS |
| SF-005 | P0 | POST | /search/tours/sync | 401 / 1401 | 401 / 1401 | PASS |
| SF-006 | P0 | POST | /search/tours/sync | 403/503 / 1007/1403/1503 | 503 / 1503 | PASS |
| SF-007 | P1 | GET | /tour/tours/search-snapshot | 401 / 1401 | 401 / 1401 | PASS |
| SF-010 | P1 | GET | /file/media/download/does-not-exist.png | 404 / 1012/1404 | 404 / 1012 | PASS |
| SF-011 | P0 | GET | /file/media/download/..%2F..%2Fapplication.properties | 400/401/404 / 1401/1400/1012/1404 | 401 / 1401 | PASS |
| SF-012 | P1 | GET | /file/media/tours | 400 / 1400 | 400 / 1400 | PASS |
| SF-013 | P2 | GET | /file/owner/999999 | 404 / 1012/1404 | 404 / 1012 | PASS |
| SF-014 | P0 | GET | /file/media/tour-orphans | 403 | 403 / 1007 | PASS |
| SF-015 | P1 | DELETE | /file/media/tour-orphans/not-there | 400/404 / 1400/1012/1404 | 400 / 1400 | PASS |
| SF-016 | P2 | POST | /file/media/uploadTour/17 | 400/415 / 1400/1415 | 400 / 1400 | PASS |
| SF-017 | P2 | POST | /file/media/uploadTour/17 | 400 / 1400 | 400 / 1400 | PASS |
| SF-018 | P2 | POST | /file/media/uploadTour/17 | 400 / 1400/1413 | 400 / 1400 | PASS |
| SF-019 | P2 | POST | /file/media/uploadTour/17 | 413 / 1413 | 413 / 1413 | PASS |
| SF-020 | P0 | POST | /auth/users/updateAvtUser/7 | 403 / 1007/1403 | 403 / 1007 | PASS |
| SF-021 | P0 | POST | /file/media/upload/location | 403 / 1007 | 403 / 1007 | PASS |
| SF-022 | P0 | POST | /file/media/uploadAvt/7 | 403 / 1007 | 403 / 1007 | PASS |
| SF-023 | P0 | POST | /file/media/tour/17/edit | 403 / 1007 | 403 / 1007 | PASS |
| SF-024 | P1 | POST | /file/media/tour/17/edit | 400 / 1400 | 400 / 1400 | PASS |
| SF-025 | P1 | POST | /file/media/upload/location | 400 / 1400 | 400 / 1400 | PASS |
| SF-030 | P1 | POST | /ai/conversations | 400 / 4000 | 400 / 4000 | PASS |
| SF-031 | P2 | POST | /ai/conversations | 415 | 415 | PASS |
| SF-032 | P1 | POST | /ai/conversations/reset | 410 / 4100 | 410 / 4100 | PASS |
| SF-033 | P1 | POST | /ai/conversations/reset | 400 / 4000 | 400 / 4000 | PASS |
| SF-034 | P1 | POST | /ai/chat | 400 / 4000 | 400 / 4000 | PASS |
| SF-035 | P1 | POST | /ai/chat | 400 / 4000 | 400 / 4000 | PASS |
| SF-036 | P1 | POST | /ai/chat | 400 / 4000 | 400 / 4000 | PASS |
| SF-037 | P1 | POST | /ai/chat | 410 / 4100 | 410 / 4100 | PASS |
| SF-038 | P1 | POST | /ai/chat | 422 / 4220 | 422 / 4220 | PASS |
| SF-039 | P2 | POST | /ai/chat | 400/413 / 4000/4130 | 400 / 4000 | PASS |
| SF-040 | P1 | POST | /ai/booking-help | 400 / 4000 | 400 / 4000 | PASS |
| SF-041 | P1 | POST | /ai/booking-help | 200 | 200 / 1000 | PASS |
| SF-042 | P0 | POST | /ai/booking-help | 401 / 4010/1401 | 401 / 1401 | PASS |
| SF-043 | P1 | POST | /ai/booking-help | 400 / 4000 | 400 / 4000 | PASS |
| SF-044 | P2 | GET | http://localhost:8091/ai/health | 200 | 200 / 1000 | PASS |
| SF-045 | P2 | GET | /ai/policies | 200 | 200 / 1000 | PASS |
| SF-050 | P2 | GET | /socket.io/?transport=polling | 200/400 | 200 | PASS |
| SF-051 | P2 | GET | /socket.io/?EIO=4&transport=carrier-pigeon | 400/401 | 400 / 0 | PASS |
| SF-052 | P1 | GET | /socket.io/?EIO=4&transport=polling | 401/403 | 401 | PASS |
| SF-060 | P2 | GET | /file/media/download/does-not-exist.png | 429 / n/a (body rỗng) | 429 | PASS |
| SF-061 | P2 | POST | /ai/conversations | 429 / 1429/4290 | 429 / 1429 | PASS |
| SF-070 | P2 | GET | /auth/users/list | 503 / 1503 |  | SKIP |

### Ghi chú ca kiểm thử

| ID | Ghi chú |
| --- | --- |
| AU-007 | thiết kế chống liệt kê: ACCOUNT_INACTIVE không bị lộ ở luồng đăng nhập |
| AU-028 | khoá tài khoản được áp dụng trên đường refresh |
| BK-025 | quan sát: PageSlice kẹp page/size thay vì từ chối chúng |
| BK-026 | quan sát: PageSlice kẹp page/size thay vì từ chối chúng |
| BK-033 | quan sát: phân trang bị kẹp và kết quả vẫn giới hạn trong phạm vi người gọi |
| BK-050 | bookingId=41 |
| CH-002 | quan sát: phân trang bị kẹp ở đây, booking/invoice từ chối phân trang xấu |
| CH-023 | một change đã hoàn tất bị từ chối trước khi hành động được parse |
| GW-RL-01 | attempts=4 retry-after=60 |
| GW-RL-02 | attempts=4 retry-after=60 |
| PY-022 | 01 = không tìm thấy đơn hàng |
| PY-026 | txnRef=51029da30e0f4ee1b15289eab8d2fccd |
| PY-027 | phát lại idempotent |
| PY-045 | quan sát: ref không tồn tại trả lịch sử rỗng chứ không phải 404 |
| PY-055 | quan sát: phân trang bị kẹp ở đây, các service khác từ chối phân trang xấu |
| RV-004 | điều kiện đủ điều kiện được kiểm tra trước khi tra cứu tour |
| SF-011 | separator được mã hoá làm rời khỏi prefix công khai, nên gateway trả 401 |
| SF-015 | quan sát: endpoint này báo một orphan không tồn tại là 400 |
| SF-016 | quan sát: file-service trả 400 trong khi các service khác trả 415 |
| SF-031 | quan sát: lỗi media-type được trả lời bởi mặc định của Spring, không phải envelope AI |
| SF-038 | scope guard chạy trước khi tra cứu hội thoại |
| SF-039 | guard kích thước ở edge trả lời trước khi ai-service kịp báo AI_INPUT_TOO_LARGE |
| SF-041 | phải nhắc người gọi đăng nhập, không làm lộ dữ liệu |
| SF-042 | edge từ chối token không hợp lệ trước khi ai-service nhìn thấy nó |
| SF-050 | quan sát: phiên bản EIO không được ép buộc |
| SF-060 | tuỳ chọn (opt-in) |
| SF-061 | attempts=1 retry-after=1611 type=AI_RATE_LIMITED |
| SF-070 | kiểm thử hạ tầng có tính phá huỷ |
| TL-004 | quan sát: phân trang không được validate ở đây (mặc định Spring Pageable) |
| TL-005 | quan sát: phân trang không được validate ở đây (mặc định Spring Pageable) |
| TL-006 | quan sát: không có cận trên rõ ràng; booking/invoice thì từ chối phân trang xấu |
| TL-027 | các route advice là công khai và parse chặt |
| UR-024 | quan sát: không kiểm tra tồn tại, id không tồn tại trông giống một người dùng không có sự kiện |

## 8. Tài liệu và tệp sinh ra

| Đường dẫn | Nội dung |
| --- | --- |
| `.runtime/api-error-test.ps1` | Toàn bộ bộ test lỗi: harness, fixture và định nghĩa của tám nhóm. |
| `.runtime/start-stack.ps1` / `.runtime/stop-stack.ps1` | Khởi động/dừng MySQL :3307 và mười JVM (nạp `.local/*.properties`). |
| `.runtime/start-rate-gateway.ps1` | Gateway thứ hai trên :8889 với ngân sách credential/AI rất nhỏ cho các ca 429 tuỳ chọn. |
| `.runtime/logs/results-err-<group>.json` | Bản ghi từng ca (status, code, body, thời gian chạy) mà các bảng trên được dựng từ đó. |
| `.runtime/logs/err-state.properties` | Fixture được cache (tài khoản dùng một lần, booking đang chờ, tham chiếu VNPay, base URL gateway có throttle). |
| `docs/API_TEST_RESULTS.md` | Báo cáo happy-path (138/138) mà bộ test này bổ sung cho nó. |