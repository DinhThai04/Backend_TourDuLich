# SGU Tour — AI service

Module Spring Boot độc lập của trợ lý SGU Tour. Cập nhật theo source ngày **01/11/2026**: Java 21, **Spring Boot 3.5.16**, **Spring AI 1.1.8**, Groq API; mặc định bind `127.0.0.1:8091`, context `/ai`.

## Phạm vi hiện có

- Hiểu yêu cầu du lịch thành kế hoạch/tiêu chí có kiểu: khu vực, điểm đến/đi, ngân sách, ngày và nhóm hành khách; tìm thêm, đọc chi tiết và so sánh tour.
- Giữ tiêu chí phiên: câu “giảm ngân sách còn 2 triệu” cập nhật ngân sách, giữ miền Trung nếu chưa có yêu cầu đổi vùng rồi tìm lại bằng API backend. Giá/chỗ do backend cung cấp, không suy ra từ lời văn model.
- Trả lời 18 chính sách đã được bật theo version, cùng nguồn với trang `/policies`; câu hỏi chính sách không cần gọi model để tự viết điều khoản.
- `/booking-help` đọc booking của chính người đăng nhập qua booking service, tính thời gian theo deadline/trạng thái thật và trả lời có kiểm chứng. Không suy hạn cho booking cũ thiếu dữ liệu reservation.
- Chặn nội dung code/SQL, lệnh thực thi, dò secret/prompt injection và câu ngoài phạm vi. Không có tool chạy code, truy vấn SQL tùy ý, ghi DB, đặt tour, thu tiền hoặc gửi mail.

Model/provider hiện dùng **Groq**, model mặc định `openai/gpt-oss-120b`. Đây không phải giao diện nhận tùy ý provider URL/API key từ trình duyệt. Nguồn dữ liệu là API có schema cố định; không nạp cả DB hoặc toàn bộ frontend vào prompt. Không có vector database, embedding/RAG kho tài liệu tự do hay model chạy local.

## Chạy như một Spring Boot service

Tại `Backend_TourDuLich/ai_service`, dùng JDK 21 và Maven:

```powershell
# Chỉ copy khi chưa có .env; mở file và điền key cục bộ:
if (-not (Test-Path -LiteralPath .env)) { Copy-Item .env.example .env }
mvn spring-boot:run
```

Hoặc Run `com.example.ai_service.AiServiceApplication` trong IDE, working directory là module. Không cần launcher PS1. Module có Dockerfile riêng và chạy qua profile `ai` của compose gốc khi cần container hoá. Trong container, compose mặc định `AI_ENABLED=false` và tự trỏ catalog/booking sang `api_gateway`/`booking_service`, nên hãy đặt cả `GROQ_API_KEY` và `AI_ENABLED=true` nếu muốn gọi model.

`.env` chỉ được bộ nạp riêng đọc **GROQ_API_KEY**; environment tiến trình được ưu tiên. Bộ nạp tìm tại working directory, `ai_service/.env` hoặc `Backend_TourDuLich/ai_service/.env` tương ứng cách chạy. Không thực thi nội dung file như shell. Có key thì mặc định bật model; đặt `AI_ENABLED=false` trong environment/IDE để tắt model và không đọc key từ file. Các biến khác đặt ở environment/IDE, không chỉ thêm vào `.env` rồi kỳ vọng được nạp.

| Biến | Mặc định / tác dụng |
|---|---|
| GROQ_API_KEY | Key phía server; không đưa vào frontend, URL, log hoặc repo |
| AI_ENABLED | Có key thì bật; false tắt gọi model |
| AI_PORT | 8091; nếu đổi cần chỉnh URI gateway tương ứng |
| AI_MODEL | openai/gpt-oss-120b |
| AI_MAX_COMPLETION_TOKENS | 1024 |
| AI_SESSION_TTL | 30m |
| AI_CATALOG_BASE_URL | http://127.0.0.1:8888/api/v1/tour |
| AI_BOOKING_BASE_URL | http://127.0.0.1:8081/booking |
| AI_FAQ_APPROVED_VERSION | 2026-10-26-v4; phải khớp nguồn chính sách |

Các mức giới hạn/timeout khác ở `src/main/resources/application.yml`. Quota của tài khoản Groq là giới hạn bên ngoài; các giới hạn ứng dụng không bảo đảm provider luôn còn quota hoặc model free luôn sẵn sàng.

Client catalog/booking chỉ chấp nhận HTTP tới loopback hoặc đúng tên service compose (`api_gateway`, `booking_service`) với path cố định; không dùng các biến base URL để trỏ tùy ý ra Internet. Khi chuyển sang host khác cần đánh giá và điều chỉnh ranh giới này, không chỉ thay hostname trong environment.

## Phụ thuộc theo tính năng

| Chức năng | Cần chạy |
|---|---|
| Health trực tiếp | AI service; endpoint không gọi provider |
| Trang/chính sách đã bật | AI + gateway; không cần key để sinh câu trả lời FAQ |
| Tư vấn/tìm tour bằng ngôn ngữ tự nhiên | AI + Groq key + gateway + tour và các phụ thuộc pricing/file/DB |
| Tra booking chính chủ | AI + gateway + auth + booking + DB; dùng JWT của chính tài khoản |

AI không có datasource, không cần import SQL riêng. Dữ liệu toàn đồ án dựng bằng [SQL tổng](../data_seed/README.md). Search/Elasticsearch không phải nguồn SQL trực tiếp của AI; catalog AI đi qua API tour.

## API

Frontend dùng gateway `http://localhost:8888/api/v1/ai`; các URL dưới là path sau prefix đó.

| Method / path | Quyền / nội dung |
|---|---|
| POST `/conversations` | Public, JSON `{}`, tạo phiên |
| POST `/chat` | Public với conversationId hợp lệ; requestId UUID, message; kiểm tra scope/giới hạn |
| POST `/conversations/reset` | Public với conversationId; xóa phiên cũ và cấp phiên mới |
| GET `/policies` | Public; nguồn chính sách đã duyệt theo version |
| POST `/booking-help` | JWT website, chỉ dữ liệu chủ tài khoản; body question, selectedBookingId tùy chọn |

Health dùng trực tiếp **GET `http://127.0.0.1:8091/ai/health`**; gateway không khai báo route health public. Kết quả UP/configured không chứng minh đã gọi Groq thành công; `providerConnection` là NOT_CHECKED.

Ví dụ chat, thay ID bằng kết quả tạo phiên và tạo requestId mới cho lượt mới:

```json
{
  "conversationId": "<conversationId-tu-POST-conversations>",
  "requestId": "11111111-1111-4111-8111-111111111111",
  "message": "Tôi muốn đi miền Trung, khoảng 3 triệu cho một người lớn"
}
```

Gateway bỏ Authorization/Cookie khỏi chat public và chính sách; riêng booking-help giữ Authorization để booking service xác thực/kiểm tra owner. Không gửi hồ sơ/đơn riêng tư sang model để nhờ nó tự phân quyền. Frontend đã có client và widget, không cần thêm chatbot/n8n webhook khác.

## Phiên, dữ liệu và giới hạn

- Phiên lưu **RAM**, token ngẫu nhiên 43 ký tự, TTL mặc định 30 phút, tối đa 100 phiên; restart làm mất phiên. Frontend giữ tham chiếu phiên theo tab/owner bằng sessionStorage, không biến Redis/DB thành kho hội thoại bền.
- Lưu criteria và lịch sử rút gọn tối đa 10 lượt; cache kết quả theo requestId tối đa 20 mục để tránh lặp. Một phiên chỉ xử lý một lượt tại một thời điểm.
- Thay tiêu chí thì backend đọc/tìm lại qua API; kết quả giá/chỗ có thời điểm kiểm tra và cần xác nhận lại ở bước booking. Không mặc định coi kết quả hội thoại trước là tồn kho hiện tại.
- Tin nhắn tối đa 16.000 Unicode code point (tối đa 64.000 byte UTF-8) qua kiểm tra policy; body JSON được chặn ở 65.024 byte trước khi xử lý, quá giới hạn trả 413. Có kiểm tra JSON/field/token/requestId. Đây là giới hạn transport/policy; từng lượt gọi model còn bị chặn bởi ngân sách prompt `app.ai.guard.prompt-utf8-bytes` (mặc định 11.000 byte UTF-8 cho system + lịch sử + tin nhắn), nên tin nhắn rất dài hoặc tiếng Việt nhiều dấu có thể bị trả 400 `AI_INPUT_TOO_LARGE` dù chưa chạm 16.000 code point. Chặn mẫu mã/lệnh/secret chỉ là một lớp phòng vệ; ranh giới chính là kế hoạch có kiểu, API đọc cố định, quyền owner và không có công cụ thực thi.
- Gateway giới hạn mặc định theo client: chat 6/phút, 40/ngày, tạo 20/30 phút, reset 10/phút. AI còn có bộ giới hạn model/token, deadline và kiểm tra response. Không tắt các giới hạn chỉ để né lỗi 429.
- FAQ dùng `src/main/resources/ai/policies-v2.json`, nhưng **version nội dung hiện là `2026-10-26-v4`**, 18 mục. File tên v2 không có nghĩa đang dùng chính sách v2 cũ. Chức năng chưa hỗ trợ trả lời rõ chưa có chính sách, không tự bịa phí/điều kiện.

## Kiểm thử và xử lý lỗi

```powershell
mvn '-Dtest=*Test' test
```

Test mock/H2 hoặc HTTP fixture không cần key thật cho từng ca; các kiểm tra live có điều kiện riêng. Việc chạy test mặc định không đồng nghĩa đã kiểm chứng provider thật. Không mở debug HTTP để ghi key/body nhạy cảm.

| Biểu hiện | Kiểm tra |
|---|---|
| Model chưa cấu hình / 503 | Key trong environment hoặc `.env`, AI_ENABLED và dependency backend |
| 429 | Giới hạn app/provider, chờ Retry-After; tránh retry vòng lặp |
| Phiên hết hạn / không tồn tại | Tạo phiên mới; thường do TTL hoặc restart |
| 409 | Cùng phiên đang xử lý hoặc requestId được dùng lại với nội dung khác |
| AI_SCOPE_REJECTED / 422 | Nội dung bị chặn theo scope, không chạy mã hoặc SQL nhận từ chat |
| Booking-help 401/403 | Login/refresh, trạng thái tài khoản, owner; không gửi booking ID người khác để vượt quyền |
| FAQ không khởi động | Version duyệt phải khớp policies-v2.json; không bỏ qua kiểm tra bằng chat instruction |

[Chỉ mục tài liệu AI](../docs/ai/README.md) · [README backend](../README.md) · [Tổng quan hệ thống](../docs/PROJECT.md).
