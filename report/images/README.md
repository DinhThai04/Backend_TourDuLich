# Thư mục ảnh minh họa

Ảnh trong thư mục này được dùng bởi các lệnh `\screenshot{...}` (Phần IV)
và lệnh chèn logo trên bìa. Tên tệp phải khớp chính xác với tham số đầu
tiên của `\screenshot`.

Các ảnh được tái tạo từ giao diện thật bằng `mockups/render.mjs` (HTML tĩnh
dựng theo đúng nhận diện thương hiệu của dự án, chụp bằng Chrome headless
ở độ phân giải 1440×1000). Có thể chụp lại màn hình thật và ghi đè cùng tên
tệp mà không cần sửa LaTeX.

## Ảnh bìa

| Tệp | Nội dung |
|---|---|
| `sgu-logo.png` | Logo trường dùng trên trang bìa |

## Thứ tự hình trong Phần IV

Số hình tương ứng với thứ tự xuất hiện trong `chapters/04-trien-khai.tex`.

| Hình | Tệp | Nội dung |
|---|---|---|
| IV.1 | `14-admin-tong-quan.png` | Tổng quan vận hành dành cho quản trị viên |
| IV.2 | `15-admin-nguoi-dung.png` | Quản lý người dùng và trạng thái tài khoản |
| IV.3 | `16-admin-tour.png` | Quản lý tour |
| IV.4 | `17-admin-tour-form.png` | Biểu mẫu thêm tour mới |
| IV.5 | `18-admin-lich-khoi-hanh.png` | Lịch khởi hành và sức chứa |
| IV.6 | `19-admin-lich-trinh.png` | Lịch trình theo ngày |
| IV.7 | `20-admin-dia-diem.png` | Địa điểm khởi hành và điểm đến |
| IV.8 | `21-admin-gia.png` | Combo giá theo nhóm tuổi |
| IV.9 | `22-admin-booking.png` | Quản lý booking và trạng thái giữ chỗ |
| IV.10 | `23-admin-hoa-don.png` | Quản lý hóa đơn |
| IV.11 | `24-admin-phan-quyen.png` | Vai trò và ma trận quyền |
| IV.12 | `25-admin-danh-gia.png` | Duyệt đánh giá tour |
| IV.13 | `26-n8n-email.png` | Workflow n8n nhắc thanh toán/khởi hành |
| IV.14 | `27-ai-flow.png` | Luồng xử lý trợ lý AI có kiểm soát |
| IV.15 | `10-dang-nhap.png` | Đăng nhập (email/mật khẩu hoặc Google) |
| IV.16 | `11-dang-ky.png` | Đăng ký tài khoản khách hàng |
| IV.17 | `01-trang-chu.png` | Trang chủ: banner, tìm kiếm, tour nổi bật |
| IV.18 | `02-danh-sach-tour.png` | Danh sách tour với bộ lọc và phân trang |
| IV.19 | `03-chi-tiet-tour.png` | Chi tiết tour: gallery, lịch trình, giá, lịch khởi hành |
| IV.20 | `04-dat-tour.png` | Biểu mẫu đặt tour và danh sách hành khách |
| IV.21 | `05-thanh-toan.png` | Chọn phương thức thanh toán VNPay/MoMo |
| IV.22 | `06-vnpay.png` | Cổng thanh toán VNPay sandbox |
| IV.23 | `07-hoa-don.png` | Hóa đơn thanh toán thành công |
| IV.24 | `08-lich-su-dat-tour.png` | Lịch sử đặt tour và trạng thái thanh toán |
| IV.25 | `09-danh-gia.png` | Đánh giá chuyến đi |
| IV.26 | `13-thong-tin-ca-nhan.png` | Thông tin cá nhân và bảo mật tài khoản |
| IV.27 | `12-tro-ly-ai.png` | Trợ lý AI tư vấn tour và tra cứu booking |

## Cách tái tạo ảnh

```bash
node mockups/render.mjs
```

Script dựng các trang HTML trong `mockups/html/` rồi gọi Chrome headless
(`--headless --screenshot`) để xuất PNG kích thước 1440×1000 vào thư mục
này. Yêu cầu Node.js 20+ và Google Chrome. Đường dẫn Chrome có thể chỉnh
trong `render.mjs` nếu cài ở vị trí khác.

Khi tệp ảnh chưa tồn tại, `\screenshot` hiển thị khung giữ chỗ và báo cáo
vẫn biên dịch bình thường.
