# Phân tích lỗi & khoảng trống kiểm thử API - SGU Tour backend

Nguồn dữ liệu: `docs/API_ERROR_TEST_RESULTS.md` (lần chạy `.runtime/api-error-test.ps1` ngày 2026-11-17, 326 ca: 325 PASS / 0 FAIL / 1 SKIP).

Mục đích của tài liệu này: trả lời hai câu hỏi

1. Có API nào **trả về chưa đúng lỗi** không?
2. Có API nào **còn thiếu trường hợp trả về lỗi** không?

> Cách đọc: báo cáo gốc ghi **0 FAIL**, nghĩa là không ca nào lệch so với cột `Mong đợi`. Nhưng cột đó được viết dưới dạng "cho phép nhiều mã"
> (ví dụ `400/503 / 1400/1503`), nên vẫn tồn tại các hành vi **lỏng / thiếu nhất quán** — đây là nội dung mục 2 dưới đây.
> Các mục đánh dấu **(suy luận)** là do tôi phân tích từ ma trận ca test, không phải kết luận được ghi sẵn trong báo cáo gốc.

---

## 1. Kết luận nhanh

| Câu hỏi | Trả lời |
| --- | --- |
| Có ca nào FAIL? | Không. 325/325 PASS, 0 FAIL, không có 5xx, không rò rỉ stack trace, không rò rỉ dữ liệu. |
| Có API trả sai hoàn toàn so với hợp đồng? | Không — mọi ca đều khớp ít nhất một giá trị trong cột `Mong đợi`. |
| Có API trả lỗi **chưa chuẩn về ngữ nghĩa**? | Có, **6 nhóm** vấn đề (mục 2): sai mã trạng thái, chấp nhận input xấu, envelope thiếu `code`, mã 404 không nhất quán, thứ tự guard che lỗi đúng, và các ca bị edge trả lời thay service. |
| Có thiếu ca lỗi? | Có. **6 khoảng trống đã thừa nhận** (mục 3.1) + **12 nhóm chưa có ca nào** (mục 3.2). Nghiêm trọng nhất: circuit breaker của gateway (`SF-070` bị SKIP), chữ ký MoMo, và outage của 7 service còn lại. |

---

## 2. Những chỗ trả lỗi "chưa đúng" (vẫn PASS vì hợp đồng cho phép)

### 2.1. Sai / không có mã trạng thái đúng như bản chất lỗi

| ID | Endpoint | Thực tế | Đáng lẽ / vấn đề | Mức độ |
| --- | --- | --- | --- | --- |
| SF-015 | `DELETE /file/media/tour-orphans/not-there` | `400 / 1400` | **404** — xoá một ảnh orphan không tồn tại | Trung bình |
| SF-016 | `POST /file/media/uploadTour/17` (gửi JSON) | `400 / 1400` | auth-service trả **415/1415** cho cùng lỗi → không nhất quán media-type | Thấp |
| SF-039 | `POST /ai/chat` (message 20 kB) | `400 / 4000` | **413 / AI_INPUT_TOO_LARGE**; guard kích thước ở edge chặn trước nên 413 không bao giờ chạm tới | Trung bình |
| SF-011 | `GET /file/media/download/..%2F..%2Fapplication.properties` | `401 / 1401` | Đáng lẽ file-service trả **400/404**; traversal vẫn bị chặn nhưng do edge trả lời | Thấp |
| GW-011 | `PUT /auth/users/login` | `401 / 1401` | **405 / 1405** — sai phương thức; vì danh sách route công khai là theo từng phương thức | Thấp |
| GW-008 | `GET /booking/internal/mail/reminders/dispatch` | `503 / 1503` | **405 / 1405**; guard "chưa cấu hình key" chạy trước guard phương thức | Thấp |

### 2.2. Chấp nhận input sai thay vì trả 400 (silent clamp)

| ID | Endpoint | Input | Thực tế |
| --- | --- | --- | --- |
| TL-004 | `GET /tour/tours/search` | `page=-1` | `200` (bị kẹp giá trị) |
| TL-005 | `GET /tour/tours/search` | `size=0` | `200` |
| TL-006 | `GET /tour/tours/search` | `size=1000` | `200` |
| BK-025 | `GET /booking/book/all` | `page=-1&size=20&mine=true` | `200` |
| BK-026 | `GET /booking/book/all` | `page=0&size=0&mine=true` | `200` |
| BK-033 | `GET /booking/customer/all` | `page=-1&size=0` | `200` |
| CH-002 | `GET /booking/changes/eligible` | `page=-1` | `200` |
| PY-055 | `GET /invoice/direct/all` | `page=-1` | `200` |

Vấn đề: **cùng hệ thống, hai chuẩn khác nhau** — luồng tạo booking/hoá đơn lại từ chối phân trang xấu, còn các list trên thì kẹp im lặng.

Cùng dạng "che lỗi thành 200 + list rỗng" với id cha không tồn tại:

| ID | Endpoint | Thực tế | Ghi chú |
| --- | --- | --- | --- |
| UR-024 | `GET /auth/users/999999/events` | `200` rỗng | Không phân biệt được "không có tài khoản" với "không có sự kiện" |
| RV-012 | `GET /auth/reviews/tour/999999` | `200` rỗng | **(suy luận)** cùng pattern, chưa được ghi chú trong mục 5 báo cáo gốc |
| TL-026 | `GET /tour/itineraries/tour/999999` | `200` rỗng | **(suy luận)** cùng pattern |
| PY-045 | `GET /payment/reconciliation/NOPE-2026-0001/history` | `200` rỗng | Đã được ghi chú |

### 2.3. Envelope thiếu `code` nghiệp vụ

Các ca chỉ trả đúng mã HTTP, **không kèm `code`**, trong khi phần lớn hệ thống trả envelope đầy đủ:

| Nhóm | Ca | Thực tế |
| --- | --- | --- |
| Payment VNPay - tạo thanh toán | PY-011, PY-012, PY-015 | `400` trần |
| Payment VNPay - tạo thanh toán | PY-013 | `404` trần |
| Payment VNPay - tạo thanh toán | PY-014 | `403` trần |
| 403 không body | LC-001, LC-006, TL-011, TL-017, PD-002, PD-006, PD-024, PD-031, PD-041, PD-051, GW-014 | `403` trần |
| Hạ tầng / edge | PY-034 (`503`), SF-031 (`415` do Spring mặc định), SF-052 (`401`), SF-060 (`429` body rỗng) | không có `code` |

Thống kê: **12 ca `403` trần** so với **36 ca `403` có code** (`1007` hoặc `1403`).

Hệ quả: client không thể xử lý lỗi thống nhất — cùng một loại "không có quyền" mà chỗ có mã, chỗ không.

> Ghi chú: điểm này **chưa được liệt kê** trong mục 5 của báo cáo gốc; đây là phát hiện bổ sung khi rà cột `Thực tế`.

### 2.4. Mã 404 không nhất quán ngay trong cùng endpoint

| Endpoint | Ca GET | Ca DELETE/PUT |
| --- | --- | --- |
| `/booking/book/{id}` | BK-020 (id=0) → `404 / 1012` | BK-027 (id=999999) → `404 / 1404` |
| `/tour/tour-departures/{id}` | PD-020 → `404 / 1014` | PD-029 → `404 / 1404` |
| `/auth/users/{id}` | UR-001 → `404 / 1028` | UR-021 (delete) → `404 / 1404` |
| `/tour/locations/{id}` | — | LC-005 → `404 / 1404` (dù `1016` được phép) |

Tức "không tìm thấy" lúc trả mã riêng của entity (`10xx`), lúc trả `1404` chung — tuỳ verb/đường dẫn.

### 2.5. Thứ tự guard che mất lỗi "đúng"

| ID | Hành vi | Hệ quả |
| --- | --- | --- |
| RV-004 | `POST /auth/reviews` trả `403/1403` **trước khi** validate `tourId` | id tour rác không bao giờ nhận được `400`; ngược lại cũng không thể review tour rác |
| PY-049 | `POST /payment/reconciliation/{ref}/resolve` trả `400/1400` **trước khi** kiểm tra ref tồn tại | body xấu che mất lỗi 404 |
| AU-007 / AU-028 | Tài khoản bị khoá trả `401/1025` y như sai mật khẩu; `ACCOUNT_INACTIVE 403/1024` chỉ xuất hiện ở luồng refresh | Cố ý chống enumeration; khoá vẫn được áp dụng, refresh token phát hành trước khi khoá bị từ chối |
| SF-038 | scope guard chạy trước khi tra cứu hội thoại | Hội thoại của người khác trả lỗi scope thay vì 404 |

### 2.6. Lỗi bị edge trả lời thay service (mất ngữ nghĩa nghiệp vụ)

| ID | Endpoint | Thực tế | Ý nghĩa |
| --- | --- | --- | --- |
| TL-021..023, TL-033..036, RV-011, AU-030 | các đường dẫn đọc được bằng GET nhưng gọi bằng phương thức khác | `401 / 1401` | Đáng lẽ `405`; edge quyết định theo cặp phương thức + đường dẫn trước khi route |
| SF-011 | download với separator mã hoá | `401 / 1401` | Ra khỏi prefix công khai nên không khớp route |
| SF-070 | `GET /auth/users/list` khi auth_service chết | **SKIP** | Chưa chứng minh được `503/1503` của circuit breaker |

---

## 3. Những trường hợp lỗi còn thiếu

### 3.1. Đã thừa nhận trong mục 6 của báo cáo gốc

| Khu vực | Thiếu gì | Cách xử lý |
| --- | --- | --- |
| search_service: nhánh validation | SF-001..004 đều nhận 503 nên **validation 400/1400 của `/search/**` chưa từng được kiểm chứng thật** | Dựng Elasticsearch/Redis rồi chạy lại nhóm `search-file-ai` |
| ai_service: nhánh thành công + lỗi upstream | Model bị tắt (không có Groq key) → không có ca **timeout / 429 / 5xx từ Groq**, không có ca hội thoại thành công | Cấu hình key ở môi trường test riêng |
| file_service: upload Cloudinary | Credentials là placeholder → không có ca **upload thất bại, sai credentials, timeout**; chỉ có policy SVG/quá size/thiếu part | Dùng Cloudinary test account |
| payment_service: MoMo | Chưa cấu hình → **chữ ký IPN, sai amount, sai partnerCode, replay** hoàn toàn chưa test | Cấu hình MoMo sandbox |
| VNPay: return URL + refund | Chỉ có nhánh chữ ký hỏng; **return thành công trên trình duyệt** và luồng refund/hoàn tiền chưa có | Bổ sung ca happy-path + refund |
| Gateway: circuit breaker | `SF-070` bị **SKIP** vì phải dừng auth_service (phá vỡ cả lượt chạy) | Chạy `-IncludeInfra` trên instance cô lập |

### 3.2. Chưa có ca nào trong ma trận (suy luận từ danh sách ca)

| # | Nhóm thiếu | Chi tiết | Đề xuất ưu tiên |
| --- | --- | --- | --- |
| 1 | **Dependency outage cho 7 service còn lại** | Chỉ search tình cờ phủ 503 (`SF-001..006`), auth thì bị SKIP. Không có ca nào cho booking/payment/tour/pricing/file/invoice/ai chết → chưa biết gateway trả `503/1503`, treo, hay 500 | **Cao** |
| 2 | **Concurrency / double-submit** | Chỉ có replay IPN (`PY-027`) và change no-op (`CH-023`). Thiếu: 2 request tạo booking cùng lúc, 2 hold tranh 1 ghế, retry tạo payment, double refund | **Cao** |
| 3 | **Payload quá lớn (413)** | Chỉ `TL-030` (advice 16 kB) và `SF-039` (AI 20 kB). Thiếu 413 cho tạo tour/booking/review | Trung bình |
| 4 | **Malformed JSON / sai Content-Type** | Chỉ có ở auth+gateway (`GW-012`, `GW-013`) và vài chỗ lẻ (`LC-002`, `TL-019`, `SF-016`, `SF-031`). tour/booking/payment/invoice chưa có | Trung bình |
| 5 | **Sai phương thức (405)** | Gần như không kiểm: chỉ `GW-008`, `GW-011`; các ca "public read" trả 401 nên **hành vi 405 của service chưa được xác nhận ở hầu hết endpoint** | Trung bình |
| 6 | **Giới hạn phân trang** | Chỉ `/payment/reconciliation?page=100001` (`PY-043`). Các endpoint clamp không có ca chứng minh từ chối — vì thực tế chúng **không** từ chối | Trung bình |
| 7 | **Rate limit** | Chỉ login/register (`GW-RL-01/02`), AI (`SF-061`), file download (`SF-060`). Thiếu 429 cho booking/payment/search | Thấp |
| 8 | **Lỗi DB / tầng lưu trữ** | Không có ca duplicate key, deadlock, query timeout; chỉ có 409 nghiệp vụ (`BK-032`, `PD-052`) | Thấp |
| 9 | **Token lifecycle** | Chưa phân biệt token **hết hạn** vs **bị thu hồi**; chưa test refresh sau logout; chưa test dùng lại refresh token cũ | Trung bình |
| 10 | **Bảo mật đầu vào** | Mới có 1 ca traversal (`SF-011`); không có SQL/NoSQL injection, không có XSS trong nội dung review/advice | Trung bình |
| 11 | **5xx / rò rỉ stack trace** | Không có ca nào **cố tình** gây lỗi server; kết luận "không lộ stack trace" hiện chỉ dựa trên việc không ca nào trả 5xx | Thấp |
| 12 | **Phương thức / CORS khác** | Chỉ `OPTIONS` trên `/auth/users/login` (`GW-014/015`); không có HEAD/TRACE, không có preflight ở service khác | Thấp |

---

## 4. Bảng theo dõi việc cần làm

| Ưu tiên | Việc cần làm | Liên quan |
| --- | --- | --- |
| P1 | Bổ sung ca outage cho 7 service downstream của gateway | Mục 3.2 #1 |
| P1 | Chạy `SF-070` bằng `-IncludeInfra` trên instance cô lập | Mục 3.1 |
| P1 | Test chữ ký/amount/IPN của MoMo sau khi cấu hình sandbox | Mục 3.1 |
| P2 | Thống nhất envelope cho payment VNPay (thêm `code` cho PY-011..015) và các ca 403 trần | Mục 2.3 |
| P2 | Thống nhất mã 404: chọn `10xx` theo entity hoặc `1404` chung, không lẫn lộn | Mục 2.4 |
| P2 | Quyết định chính sách phân trang: từ chối (400) hay kẹp (200) — áp dụng đồng nhất | Mục 2.2 |
| P2 | Thêm ca concurrency: double-submit booking, tranh ghế, retry payment | Mục 3.2 #2 |
| P3 | Sửa SF-015 trả 404; SF-016 thống nhất 415; SF-039 cho phép 413 đi qua hoặc ghi rõ hợp đồng 400 | Mục 2.1 |
| P3 | Bổ sung 413/415/malformed JSON và 405 cho các module còn lại | Mục 3.2 #3, #4, #5 |
| P3 | Bổ sung 429 cho booking/payment/search; test token hết hạn vs thu hồi | Mục 3.2 #7, #9 |

---

## 5. Phụ lục: danh sách ID liên quan

**Trả lỗi chưa chuẩn (vẫn PASS)**
`SF-015`, `SF-016`, `SF-039`, `SF-011`, `GW-011`, `GW-008`,
`TL-004`, `TL-005`, `TL-006`, `BK-025`, `BK-026`, `BK-033`, `CH-002`, `PY-055`,
`UR-024`, `RV-012`, `TL-026`, `PY-045`,
`PY-011`, `PY-012`, `PY-013`, `PY-014`, `PY-015`, `PY-034`, `SF-031`, `SF-052`, `SF-060`,
`LC-001`, `LC-006`, `TL-011`, `TL-017`, `PD-002`, `PD-006`, `PD-024`, `PD-031`, `PD-041`, `PD-051`,
`BK-020`/`BK-027`, `PD-020`/`PD-029`, `UR-001`/`UR-021`, `LC-005`,
`RV-004`, `PY-049`, `AU-007`, `AU-028`, `SF-038`,
`TL-021..023`, `TL-033..036`, `RV-011`, `AU-030`.

**Chưa kiểm chứng / bỏ qua**
`SF-070` (SKIP), `SF-001..004` (chỉ chạm nhánh 503), `SF-030..043` (AI không có model), `SF-017..019` / `TL-018..020` / `LC-007` (Cloudinary placeholder), `PY-034` / `PY-035` (MoMo chưa cấu hình), `PY-025` (chỉ nhánh lỗi của return URL).

**Bất biến đã kiểm chứng tốt (không cần lo)**
`BK-024`, `BK-028`, `CH-005`, `CH-014`, `PY-003`, `PY-032`, `PY-053`, `UR-003`, `UR-015`, `UR-022` (quyền sở hữu);
`BK-008` (giới hạn ghế), `PD-030`, `CH-024` (optimistic lock); `PY-020..024` (chữ ký VNPay); `PY-027`, `CH-023` (idempotent).