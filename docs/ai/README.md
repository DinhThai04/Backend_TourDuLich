# SGU Tour — AI service

Trợ lý AI là module Spring Boot riêng trong [`ai_service`](../../ai_service/README.md), chạy ở cổng 8091 với context `/ai`, gọi Groq qua Spring AI và chỉ đọc dữ liệu qua API backend. Ứng dụng không có datasource, không đặt tour/thanh toán/gửi mail và không chạy code hay SQL.

Hướng dẫn chạy, biến môi trường, hợp đồng API, giới hạn và xử lý lỗi nằm tại **[README ai_service](../../ai_service/README.md)**.

## Phạm vi hiện có

| Phần | Nội dung |
|---|---|
| Tư vấn tour | Phân tích vùng/điểm đến, ngân sách, ngày và nhóm hành khách; tìm, xem chi tiết và so sánh tour |
| Phiên hội thoại | Giữ tiêu chí giữa các lượt, tìm lại qua API khi đổi nhu cầu; phiên lưu RAM, TTL mặc định 30 phút |
| Chính sách | Trả lời 18 mục đã duyệt theo version; nguồn runtime là [`policies-v2.json`](../../ai_service/src/main/resources/ai/policies-v2.json) |
| Booking cá nhân | `/booking-help` cần JWT, chỉ đọc booking của chính tài khoản qua booking service |
| Bảo vệ | Giới hạn input/JSON/request/token/timeout, chặn nội dung ngoài phạm vi và không cấp công cụ thực thi |

Trang `/policies` của frontend đọc cùng nguồn chính sách với chatbot. Giá, lịch và số chỗ luôn lấy từ backend tại thời điểm hỏi; kết quả hội thoại không thay thế bước xác nhận khi đặt tour.

## Chạy nhanh

Tại `Backend_TourDuLich/ai_service`:

```powershell
# Chỉ copy khi chưa có .env, sau đó điền GROQ_API_KEY cục bộ:
if (-not (Test-Path -LiteralPath .env)) { Copy-Item .env.example .env }
mvn spring-boot:run
```

Hoặc Run `com.example.ai_service.AiServiceApplication` trong IDE với working directory là module. Health trực tiếp: `http://127.0.0.1:8091/ai/health`. Muốn tắt model, đặt `AI_ENABLED=false`; FAQ và tra cứu booking vẫn hoạt động mà không cần gọi Groq.

Khi chạy cả hệ thống bằng compose, dùng profile `ai`; container cần cả `GROQ_API_KEY` và `AI_ENABLED=true` nếu muốn gọi model. Không đưa key vào frontend, URL, log hoặc repo.

## Kiểm thử

```powershell
mvn '-Dtest=*Test' test
```

Test mock/H2 không cần key thật và không chứng minh provider đang còn quota. Kịch bản thử trên website: tìm tour theo vùng/ngân sách, giảm ngân sách ở lượt sau, xem chi tiết/so sánh, đọc `/policies`, rồi đăng nhập hỏi hạn booking của chính tài khoản.

## Tài liệu liên quan

- [README ai_service](../../ai_service/README.md) — chạy, cấu hình, API và lỗi thường gặp.
- [README database](../../data_seed/README.md) — SQL tổng duy nhất của đồ án.
- [Tổng quan hệ thống](../PROJECT.md) · [Email/n8n](../mail/README.md) · [Backend](../../README.md) · [Frontend](../../../Frontend_TourDuLich/README.md).
