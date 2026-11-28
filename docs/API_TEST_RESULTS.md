# Báo cáo kiểm thử API — SGU Tour Backend

- **Ngày kiểm thử:** 2026-11-16 (Asia/Ho_Chi_Minh)
- **Phạm vi:** toàn bộ luồng happy-path của 9 microservice + API Gateway + Socket.IO
- **Nguyên tắc:** chỉ chạy luồng thành công (happy path), dùng dữ liệu seed thật trong CSDL, không kiểm thử nhánh lỗi.
- **Kết quả:** **138/138 luồng PASS** (0 FAIL)

---

## 1. Môi trường kiểm thử

| Thành phần | Cấu hình |
|---|---|
| API Gateway | `http://localhost:8888/api/v1` (bỏ tiền tố `/api/v1` khi định tuyến) |
| Cơ sở dữ liệu | MySQL 8.0.39 (instance riêng) tại `127.0.0.1:3307`, schema `dbtour_v2`, seed từ `data_seed/database_all.sql` |
| Redis | `127.0.0.1:6379` |
| Elasticsearch | `127.0.0.1:9200` |
| Các service | auth 8080, tour 8086, booking 8081, payment 8083, pricing 8084, invoice 8090, search 8089, file 8087, ai 8091 |
| Socket.IO | `127.0.0.1:8099` |
| Xác thực | JWT (gateway introspect + kiểm tra lại ở từng service) |
| Tài khoản demo | `admin@example.com/admin123` (id 1), `user1@gmail.com … user10@gmail.com/user12345` (id 3–12) |
| Công cụ | PowerShell 5.1 + `System.Net.Http.HttpClient`, log JSON theo từng request |

> Một số nhà cung cấp ngoài được cấu hình bằng credential giả để chạy nội bộ: VNPay (`TESTTMN01` / `TESTONLYSECRET0123456789`), Cloudinary (`CLOUDINARY_CLOUD_NAME=xxcku2am`), `MAIL_ENABLED=false`. MoMo và Groq AI không có credential nên các luồng phụ thuộc 2 dịch vụ này được liệt kê riêng ở mục 8.

---

## 2. Kết quả tổng hợp

| Nhóm | Số luồng | Pass | Fail |
|---|---:|---:|---:|
| Auth / User / Role / Permission / Review | 37 | 37 | 0 |
| Tour / Pricing / Location / Vehicle / Departure | 39 | 39 | 0 |
| Booking / Change / Payment / Invoice / Search / File / AI | 54 | 54 | 0 |
| Extra (MoMo quote, đối soát) | 8 | 8 | 0 |
| **Tổng** | **138** | **138** | **0** |

File log thô: `.runtime/logs/results-{auth,tour,booking,extra}.json` (mỗi bản ghi gồm request, response, status, thời gian).

---

## 3. Nhóm Auth / User / Role / Permission / Review (37/37)

| # | Luồng | Method & Endpoint | Status |
|---:|---|---|---:|
| 1 | Đăng nhập admin | `POST /auth/users/login` | 200 |
| 2 | Đăng nhập user1 (account 3) | `POST /auth/users/login` | 200 |
| 3 | Đăng nhập user5 (account 7) | `POST /auth/users/login` | 200 |
| 4 | Kiểm tra token (introspect nội bộ) | `POST :8080/auth/users/introspect` | 200 |
| 5 | Làm mới token admin | `POST /auth/users/refresh` | 200 |
| 6 | Đăng ký khách hàng công khai | `POST /auth/users/register` | 200 |
| 7 | Đăng nhập tài khoản vừa đăng ký | `POST /auth/users/login` | 200 |
| 8 | Đổi mật khẩu tài khoản vừa đăng ký | `POST /auth/users/Change-password` | 200 |
| 9 | Đăng nhập bằng mật khẩu mới | `POST /auth/users/login` | 200 |
| 10 | Xem hồ sơ của chính mình | `GET /auth/users/{id}` | 200 |
| 11 | Danh sách tài khoản (admin) | `GET /auth/users/list?page=0&size=5` | 200 |
| 12 | Lấy tên hiển thị theo id | `GET /auth/users/display-names?ids=1,3,7` | 200 |
| 13 | Admin tạo tài khoản quản lý | `POST /auth/users` | 200 |
| 14 | Cập nhật hồ sơ tài khoản quản lý | `POST /auth/users/UpdateUser/{id}` | 200 |
| 15 | Xem chi tiết tài khoản quản lý | `GET /auth/users/{id}` | 200 |
| 16 | Khóa tài khoản đăng ký mới | `PUT /auth/users/{id}/status` | 200 |
| 17 | Xóa tài khoản đã khóa (không có lịch sử) | `DELETE /auth/users/{id}` | 200 |
| 18 | Khóa tài khoản quản lý | `PUT /auth/users/{id}/status` | 200 |
| 19 | Lưu trữ tài khoản đã khóa | `POST /auth/users/{id}/archive` | 200 |
| 20 | Xem lịch sử trạng thái tài khoản | `GET /auth/users/{id}/events` | 200 |
| 21 | Đăng xuất admin | `POST /auth/users/logout` | 200 |
| 22 | Đăng nhập admin (sau đăng xuất) | `POST /auth/users/login` | 200 |
| 23 | Tạo vai trò | `POST /auth/roles` | 200 |
| 24 | Cập nhật vai trò | `PUT /auth/roles` | 200 |
| 25 | Danh sách vai trò | `GET /auth/roles` | 200 |
| 26 | Xem chi tiết vai trò | `GET /auth/roles/{id}` | 200 |
| 27 | Tạo quyền | `POST /auth/permissions` | 200 |
| 28 | Cập nhật quyền | `PUT /auth/permissions` | 200 |
| 29 | Danh sách quyền | `GET /auth/permissions/list` | 200 |
| 30 | Xem chi tiết quyền | `GET /auth/permissions/{id}` | 200 |
| 31 | Xóa quyền | `DELETE /auth/permissions/{id}` | 200 |
| 32 | Xóa vai trò | `DELETE /auth/roles/{id}` | 200 |
| 33 | Kiểm tra điều kiện đánh giá tour 15 (user5) | `GET /auth/reviews/eligibility?tourId=15` | 200 |
| 34 | Gửi đánh giá tour 15 (user5) | `POST /auth/reviews` | 200 |
| 35 | Đánh giá theo tour (công khai) | `GET /auth/reviews/tour/15` | 200 |
| 36 | Đánh giá điểm cao (công khai) | `GET /auth/reviews/high-rating?size=4` | 200 |
| 37 | Danh sách đánh giá (admin) | `GET /auth/reviews/list?page=0&size=5` | 200 |

---

## 4. Nhóm Tour / Pricing / Location / Vehicle / Departure (39/39)

| # | Luồng | Method & Endpoint | Status |
|---:|---|---|---:|
| 1 | Danh sách tour | `GET /tour/tours/list` | 200 |
| 2 | Chi tiết tour 1 | `GET /tour/tours/1` | 200 |
| 3 | Ba tour ngẫu nhiên | `GET /tour/tours/random-3` | 200 |
| 4 | Tìm tour theo từ khóa | `GET /tour/tours/search?title=Hà` | 200 |
| 5 | Snapshot danh mục cho search | `GET /tour/tours/search-snapshot` | 200 |
| 6 | Danh mục địa điểm tư vấn | `GET /tour/advice/locations` | 200 |
| 7 | Tư vấn tìm tour (công khai) | `POST /tour/advice/search` | 200 |
| 8 | Tạo phương tiện | `POST /tour/vehicles` | 200 |
| 9 | Danh sách phương tiện | `GET /tour/vehicles/list` | 200 |
| 10 | Tạo bảng giá tour | `POST /pricing/prices` | 200 |
| 11 | Cập nhật bảng giá tour | `PUT /pricing/prices/{id}` | 200 |
| 12 | Chi tiết bảng giá (công khai) | `GET /pricing/prices/{id}` | 200 |
| 13 | Danh sách bảng giá | `GET /pricing/prices` | 200 |
| 14 | Lấy giá theo lô (công khai) | `POST /pricing/prices/batch` | 200 |
| 15 | Tạo địa điểm (multipart) | `POST /tour/locations` | 200 |
| 16 | Cập nhật địa điểm | `PUT /tour/locations/{id}` | 200 |
| 17 | Tất cả địa điểm | `GET /tour/locations/all` | 200 |
| 18 | Địa điểm khởi hành | `GET /tour/locations/departures` | 200 |
| 19 | Địa điểm đến | `GET /tour/locations/destinations` | 200 |
| 20 | Tìm địa điểm khởi hành | `GET /tour/locations/departures/search?keyword=Hà Nội` | 200 |
| 21 | Tìm địa điểm đến | `GET /tour/locations/destinations/search?keyword=Sa` | 200 |
| 22 | Địa điểm hấp dẫn | `GET /tour/locations/destinations/attractive` | 200 |
| 23 | Tạo tour (multipart, không ảnh) | `POST /tour/tours` | 200 |
| 24 | Cập nhật tour | `PUT /tour/tours/{id}` | 200 |
| 25 | Chi tiết tour vừa tạo | `GET /tour/tours/{id}` | 200 |
| 26 | Tạo lịch trình | `POST /tour/itineraries` | 200 |
| 27 | Cập nhật lịch trình | `PUT /tour/itineraries/{id}` | 200 |
| 28 | Chi tiết lịch trình | `GET /tour/itineraries/{id}` | 200 |
| 29 | Lịch trình theo tour (công khai) | `GET /tour/itineraries/tour/{tourId}` | 200 |
| 30 | Tất cả lịch trình | `GET /tour/itineraries/all` | 200 |
| 31 | Xóa lịch trình | `DELETE /tour/itineraries/{id}` | 200 |
| 32 | Xóa tour vừa tạo | `DELETE /tour/tours/{id}` | 200 |
| 33 | Tạo lịch khởi hành mới | `POST /tour/tour-departures` | 200 |
| 34 | Cập nhật sức chứa lịch khởi hành | `PUT /tour/tour-departures/{id}` | 200 |
| 35 | Chi tiết lịch khởi hành (công khai) | `GET /tour/tour-departures/{id}` | 200 |
| 36 | Tổng quan sức chứa lịch khởi hành | `GET /tour/tour-departures/313/capacity` | 200 |
| 37 | Danh sách lịch khởi hành | `GET /tour/tour-departures/list` | 200 |
| 38 | Lịch khởi hành theo tour (công khai) | `GET /tour/tour-departures/tour/1` | 200 |
| 39 | Toàn bộ lịch sử lịch khởi hành tour 1 | `GET /tour/tour-departures/tour/1/all` | 200 |

---

## 5. Nhóm Booking / Change / Payment / Invoice / Search / File / AI (54/54)

### 5.1 Booking & hành khách

| # | Luồng | Method & Endpoint | Status |
|---:|---|---|---:|
| 1 | Tạo booking (giữ chỗ) – lịch 611 | `POST /booking/book/create` | 200 |
| 2 | Tạo booking thứ hai – lịch 626 | `POST /booking/book/create` | 200 |
| 3 | Xem booking của chính mình | `GET /booking/book/{id}` | 200 |
| 4 | Danh sách booking (admin) | `GET /booking/book/all?page=0&size=5` | 200 |
| 5 | Danh sách booking của tôi | `GET /booking/book/all?mine=true` | 200 |
| 6 | Hủy booking thứ hai | `DELETE /booking/book/{id}` | 200 |
| 7 | Danh sách hành khách theo booking 22 | `GET /booking/customer/booking/22` | 200 |
| 8 | Chi tiết hành khách | `GET /booking/customer/{id}` | 200 |
| 9 | Hành khách của tôi | `GET /booking/customer/all?mine=true` | 200 |
| 10 | Tất cả hành khách (admin) | `GET /booking/customer/all?page=0&size=5` | 200 |
| 11 | Tình trạng booking của tôi (advice) | `GET /booking/advice/mine?bookingId=22` | 200 |
| 12 | Điều kiện đánh giá tour 1 (user1) | `GET /booking/advice/review-eligibility?tourId=1` | 200 |

### 5.2 Đổi lịch & sự cố (change / shop incident)

| # | Luồng | Method & Endpoint | Status |
|---:|---|---|---:|
| 13 | Booking đủ điều kiện đổi lịch | `GET /booking/changes/eligible` | 200 |
| 14 | Yêu cầu đổi lịch của tôi | `GET /booking/changes/mine` | 200 |
| 15 | Các lịch thay thế cho booking 22 | `GET /booking/changes/options?bookingId=22` | 200 |
| 16 | Yêu cầu đổi lịch (khách, 22 → 566) | `POST /booking/changes/customer` | 200 |
| 17 | Gửi lại cùng clientKey (idempotent) | `POST /booking/changes/customer` | 200 |
| 18 | Danh sách yêu cầu đổi lịch (admin) | `GET /booking/changes/admin?tourId=1` | 200 |
| 19 | Admin duyệt yêu cầu đổi lịch | `POST /booking/changes/{id}/review` | 200 |
| 20 | Khách xem yêu cầu đã được duyệt | `GET /booking/changes/mine` | 200 |
| 21 | Đọc lịch 615 trước khi xử lý sự cố | `GET /tour/tour-departures/615` | 200 |
| 22 | Xem trước phương án thay thế sự cố | `POST /booking/changes/shop/preview` | 200 |
| 23 | Tạo phương án thay thế do sự cố | `POST /booking/changes/shop` | 200 |
| 24 | User5 xem đề xuất đổi lịch | `GET /booking/changes/mine` | 200 |
| 25 | Admin ghi nhận yêu cầu hoàn tiền | `POST /booking/changes/{id}/review` | 200 |
| 26 | Khách chọn phương án hoàn tiền | `POST /booking/changes/{id}/respond` | 200 |
| 27 | Admin ghi nhận đã hoàn tiền | `POST /booking/changes/{id}/refund` | 200 |
| 28 | Kích hoạt lịch thay thế sau khi xử lý xong | `POST /booking/changes/shop/{id}/activate` | 200 |

### 5.3 Thanh toán VNPay & đối soát

| # | Luồng | Method & Endpoint | Status |
|---:|---|---|---:|
| 29 | Báo giá thanh toán VNPay | `GET /payment/vnpay/bookings/{id}/quote` | 200 |
| 30 | Tạo URL thanh toán VNPay | `POST /payment/vnpay/bookings` | 200 |
| 31 | VNPay IPN xác nhận thanh toán | `GET /payment/vnpay/ipn?...` (chữ ký HMAC-SHA512 hợp lệ) | 200 |
| 32 | VNPay return (trình duyệt khách) | `GET /payment/vnpay/bookings/return?...` | 200 |
| 33 | Booking sau thanh toán | `GET /booking/book/{id}` → `CONFIRMED` | 200 |
| 34 | Danh sách đối soát thanh toán | `GET /payment/reconciliation?view=ACTIONABLE` | 200 |
| 35 | Lịch sử giao dịch đối soát | `GET /payment/reconciliation/{txnRef}/history` | 200 |

### 5.4 Hóa đơn

| # | Luồng | Method & Endpoint | Status |
|---:|---|---|---:|
| 36 | Hóa đơn theo booking vừa thanh toán | `GET /invoice/direct/booking/{id}` | 200 |
| 37 | Chi tiết hóa đơn | `GET /invoice/direct/{id}` | 200 |
| 38 | Danh sách hóa đơn (admin) | `GET /invoice/direct/all?page=0&size=5` | 200 |
| 39 | Tạo hóa đơn thu tiền mặt tại quầy (booking cũ 29) | `POST /invoice/direct/create` | 200 |
| 40 | Đọc lại hóa đơn quầy vừa tạo | `GET /invoice/direct/booking/29` | 200 |

### 5.5 Tìm kiếm, tệp và AI

| # | Luồng | Method & Endpoint | Status |
|---:|---|---|---:|
| 41 | Đồng bộ danh mục tìm kiếm | `POST /search/tours/sync` | 200 |
| 42 | Tìm kiếm: tất cả tour | `GET /search/tours?page=0&size=5` | 200 |
| 43 | Tìm kiếm: lọc giá 5–10 triệu | `GET /search/tours/search?basePrice=5to10` | 200 |
| 44 | Tải ảnh công khai | `GET /file/media/download/{fileName}` | 200 |
| 45 | Ảnh theo danh sách tour | `GET /file/media/tours?tourIds=1&tourIds=5` | 200 |
| 46 | Tệp theo chủ sở hữu | `GET /file/owner/{ownerId}` | 200 |
| 47 | Danh sách ảnh tour mồ côi | `GET /file/media/tour-orphans?page=0` | 200 |
| 48 | Chính sách AI (công khai) | `GET /ai/policies` | 200 |
| 49 | Tạo phiên tư vấn AI | `POST /ai/conversations` | 201 |
| 50 | Đặt lại phiên tư vấn AI | `POST /ai/conversations/reset` | 200 |
| 51 | Trợ giúp booking (ẩn danh, công khai) | `POST /ai/booking-help` | 200 |
| 52 | Trợ giúp booking của tôi (đã đăng nhập) | `POST /ai/booking-help` | 200 |
| 53 | Health AI service (gọi trực tiếp) | `GET :8091/ai/health` | 200 |
| 54 | Bắt tay Socket.IO | `GET :8099/socket.io/?EIO=4&transport=polling` | 200 |

---

## 6. Nhóm Extra: MoMo quote & đối soát thủ công (8/8)

| # | Luồng | Method & Endpoint | Status |
|---:|---|---|---:|
| 1 | Báo giá thanh toán MoMo (booking chờ 27) | `GET /payment/momo/bookings/27/quote` | 200 |
| 2 | Tạo booking phục vụ đối soát – lịch 611 | `POST /booking/book/create` | 200 |
| 3 | Báo giá VNPay cho booking đối soát | `GET /payment/vnpay/bookings/{id}/quote` | 200 |
| 4 | Tạo giao dịch VNPay chờ | `POST /payment/vnpay/bookings` | 200 |
| 5 | Đối soát: xác nhận giao dịch hết hạn chưa thu tiền | `POST /payment/reconciliation/{txnRef}/resolve` | 200 |
| 6 | Lịch sử đối soát của giao dịch vừa xử lý | `GET /payment/reconciliation/{txnRef}/history` | 200 |
| 7 | Hủy booking đối soát | `DELETE /booking/book/{id}` | 200 |
| 8 | Hóa đơn của tôi | `GET /invoice/direct/all?mine=true` | 200 |

---

## 7. Một số request/response tiêu biểu

### 7.1 Tạo booking (giữ chỗ) — `POST /booking/book/create`

```
REQ: {"tourDepartureId":"611","userId":"3","clientKey":"5a1e…",
      "listOfCustomers":[{"birthdate":"1990-05-20","gender":"Male",
        "fullName":"Nguyễn Văn Kiểm Thử","status":"BOOKED","address":"1 Nguyễn Huệ, TP.HCM"}]}

RES: {"code":1000,"message":"Created successfully","result":{
      "id":"30","accountId":"3","status":"PENDING_PAYMENT","tourDepartureId":"611",
      "message":"Đã giữ chỗ đến hạn thanh toán","reservationStatus":"HELD",
      "paymentDeadline":"2026-11-22T08:00","paymentRequiredNow":false,
      "totalAmount":"8490000","currency":"VND",
      "unitPrices":{"ADULT":"8490000.00","CHILD":"6390000.00","TODDLER":"0.00","INFANT":"500000.00"}}}
```

### 7.2 VNPay: báo giá → tạo URL → IPN → return → booking CONFIRMED

```
GET /payment/vnpay/bookings/30/quote
RES: {"result":{"amount":"8490000","unitPrices":{"ADULT":"8490000.00", … }}}

POST /payment/vnpay/bookings   REQ: {"bookingId":30,"expectedAmount":"8490000"}
RES: https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?vnp_Amount=849000000&
     vnp_TmnCode=TESTTMN01&vnp_TxnRef=8b4c880d…&vnp_SecureHash=43026202…

GET /payment/vnpay/ipn?...&vnp_SecureHash=<HMAC-SHA512 hợp lệ>
RES: {"Message":"Confirm Success","RspCode":"00"}

GET /payment/vnpay/bookings/return?... (gọi lại cùng chữ ký)
RES: {"result":{"status":"success","alreadyProcessed":true,"transactionNo":"TXN194548", … }}

GET /booking/book/30
RES: {"result":{"status":"CONFIRMED","reservationStatus":"PAID","totalAmount":"8490000", … }}
```

### 7.3 Hóa đơn tự động sau thanh toán và hóa đơn thu tiền mặt tại quầy

```
GET /invoice/direct/booking/30
RES: {"result":{"id":"24","bookingId":"30","paymentMethodId":"1","status":"PAID",
      "totalBookingTourExpense":"8490000"}}

POST /invoice/direct/create
REQ: {"bookingRequest":{"bookingId":"29","userId":"3","tourDepartureId":"641",
      "listOfCustomers":[{"fullName":"Khach le quay thu 1","birthdate":"1995-04-12",
        "gender":"true","status":"BOOKED","bookingType":"ADULT"}]}}
RES: {"result":{"id":"25","bookingId":"29","paymentMethodId":"3","status":"PAID",
      "message":"Hoá đơn được tạo thành công","totalBookingTourExpense":"8490000"}}
```

### 7.4 Đổi lịch theo yêu cầu khách rồi admin duyệt

```
POST /booking/changes/customer
REQ: {"bookingId":22,"targetId":566,"clientKey":"d703d852…",
      "reason":"Gia đình có việc riêng, xin đổi sang lịch khác cùng thời lượng"}
RES: {"result":{"id":1,"booking_id":22,"kind":"CUSTOMER","status":"PENDING","version":0,
      "source_departure_id":313,"target_departure_id":566}}

POST /booking/changes/1/review
REQ: {"action":"APPROVE","expectedVersion":0,"note":"Đã kiểm tra và chấp thuận đổi lịch"}
RES: {"result":{"id":1,"status":"APPROVED","version":1,"applied_at":"2026-11-16T19:45:47"}}
```

### 7.5 Phương án thay thế do sự cố (shop incident) — xem trước

```
POST /booking/changes/shop/preview
REQ: {"sourceId":615,"expectedVersion":1,"reasonCode":"NATURAL_DISASTER",
      "reason":"Bão lớn làm tuyến đường và bến tàu không thể vận hành an toàn",
      "evidence":"Công văn số 123/UBND về việc cấm ra khơi","originalCannotDepart":true,
      "departureAt":"2026-12-15T06:00:00","returnAt":"2026-12-17T19:30:00",
      "capacity":40,"responseDeadline":"2026-11-20T17:00:00"}
RES: {"result":{"bookingCount":1,"occupiedSeats":1,"version":1,"capacity":40}}
```

### 7.6 Đối soát thủ công — xác nhận giao dịch hết hạn chưa thu tiền

```
POST /payment/reconciliation/7ff7115b…/resolve
REQ: {"action":"UNPAID_CONFIRMED","amountVnd":8490000,"reference":"SGU-RECON-195037",
      "note":"Đã kiểm tra sao kê, khách chưa chuyển khoản","confirmed":true}
RES: {"result":{"txn_ref":"7ff7115b…","booking_id":32,"amount_vnd":8490000,
      "status":"CLOSED_UNPAID","provider":"VNPAY"}}
```

### 7.7 Trợ lý booking (AI, không cần LLM) — `POST /ai/booking-help`

```
REQ: {"message":"booking 22 con bao lau","selectedBookingId":22}   (kèm Bearer token của user1)
RES: {"result":{"handled":true,
      "reply":"Booking #22 — Đông Bắc: Hà Nội - Hà Giang … Khởi hành lúc 06:00 02/12/2026.
               Hệ thống đã ghi nhận thanh toán khớp payment/hóa đơn. …",
      "policy":{"policyKey":"P06","policyVersion":"2026-10-26-v4"}}}
```

### 7.8 Đăng ký & đánh giá

```
POST /auth/users/register
REQ: {"username":"sgureg…","email":"sgu.reg…@sgu-test.local","phone":"0901234567",
      "address":"1 Nguyễn Huệ, TP.HCM","password":"***"}
RES: {"code":1000,"result":{"id":"15","username":"sgureg…","role":{"id":2,"name":"Khách Hàng"},
      "status":"ACTIVE"}}

POST /auth/reviews   (user5, tour 15)
REQ: {"tourId":15,"rating":5,"content":"Chuyến đi kiểm thử: hướng dẫn viên chu đáo, …"}
RES: {"code":1000,"result":{"id":16,"tourId":15,"rating":5,"user":{"id":"7","username":"user5"}}}
```

---

## 8. Các luồng chưa kiểm thử được (và lý do)

| Luồng | Endpoint | Lý do |
|---|---|---|
| Đăng nhập Google | `POST /auth/users/google` | Cần Google ID token thật (OAuth) |
| Upload avatar người dùng | `POST /auth/users/{id}` (multipart ảnh) | Phụ thuộc Cloudinary (API key/secret thật) |
| Upload ảnh tour khi tạo/sửa tour | `POST /tour/tours`, `POST /tour/tours/{id}/images` | Phụ thuộc Cloudinary |
| Upload ảnh địa điểm | `POST /tour/locations`, `PUT /tour/locations/{id}` (có `img`) | Phụ thuộc Cloudinary |
| Upload ảnh tour / avatar qua file-service | `POST /file/media/uploadTour/{tourId}`, `POST /file/media/uploadAvt/{ownerId}`, `POST /file/media/upload/location`, `POST /file/media/tour/{tourId}/edit` | Phụ thuộc Cloudinary |
| Xóa ảnh tour mồ côi | `DELETE /file/media/tour-orphans/{id}` | Không có ảnh mồ côi thật (muốn tạo phải upload Cloudinary) |
| MoMo: tạo giao dịch / return / IPN | `POST /payment/momo/bookings`, `GET /payment/momo/bookings/return`, `POST /payment/momo/ipn` | Chưa cấu hình `MOMO_PARTNER_CODE/ACCESS_KEY/SECRET_KEY` (service trả 503 khi tạo) |
| Đối soát: ghi nhận đã thu tiền / hoàn tiền | `POST /payment/reconciliation/{txnRef}/resolve` với `PAYMENT_FOUND`/`REFUND_RECORDED` | Cần tình huống lệch thực tế (bằng chứng thu tiền/hoàn tiền) — không phải luồng chuẩn |
| Chat AI với LLM | `POST /ai/chat` | Cần `GROQ_API_KEY` thật |
| Tạo hành khách độc lập | `POST /booking/customer/create` | Service chủ động trả 409 (hành khách chỉ thêm qua luồng đặt tour) — không phải happy path |
| Gửi nhắc nhở qua mail nội bộ | `POST /booking/internal/mail/reminders/dispatch` | Endpoint nội bộ cho scheduler, không phải API người dùng |

> Ghi chú: `GET /auth/users/introspect` chỉ dùng nội bộ qua gateway (bị chặn 403), nên được gọi trực tiếp tới service (`:8080`) trong nhóm auth. Tương tự `GET /ai/health` không có route qua gateway nên gọi trực tiếp `:8091`.

---

## 9. Thiết lập dữ liệu kiểm thử

Để các luồng hợp lệ chạy được trên dữ liệu seed, các điều chỉnh sau đã được thực hiện (chỉ trên instance test `:3307`):

1. Xóa review seed `id=1` và review của `user_id=7, tour_id=15` để user5 (account 7) đủ điều kiện đánh giá tour 15.
2. Thêm booking cũ `id=29` (account 3, lịch 641, `CONFIRMED`) kèm 1 hành khách ADULT và **không** có `booking_reservation` → dùng cho luồng tạo hóa đơn thu tiền mặt tại quầy.
3. Các luồng mutation (tạo tour/địa điểm/phương tiện/bảng giá/lịch khởi hành, booking, đổi lịch, thanh toán) đều tạo mới rồi dọn dẹp khi có thể; riêng dữ liệu lịch sử (booking đã thanh toán, sự cố đã xử lý) được giữ lại đúng theo nghiệp vụ.
4. Trong nhóm `extra`, một giao dịch VNPay `PENDING` được đẩy `expires_at` về quá khứ để kiểm thử luồng đối soát `UNPAID_CONFIRMED` hợp lệ.

---

## 10. Cách chạy lại bộ kiểm thử

```powershell
# Yêu cầu: MySQL :3307 + Redis + Elasticsearch + 9 service + gateway + Socket.IO đang chạy.
# File .runtime/api-test.ps1 phải được lưu UTF-8 có BOM (PowerShell 5.1).

$p = 'D:\nam4\hk1\j2ee\Backend_TourDuLich\.runtime\api-test.ps1'
$t = [IO.File]::ReadAllText($p, [Text.Encoding]::UTF8)
[IO.File]::WriteAllText($p, $t, [Text.UTF8Encoding]::new($true))

# Chạy từng nhóm (auth phải chạy trước để lưu token vào state.properties):
powershell -NoProfile -ExecutionPolicy Bypass -File $p -Group auth
powershell -NoProfile -ExecutionPolicy Bypass -File $p -Group tour
powershell -NoProfile -ExecutionPolicy Bypass -File $p -Group booking
powershell -NoProfile -ExecutionPolicy Bypass -File $p -Group extra

# Hoặc chạy tất cả: -Group all
```

Kết quả mỗi lần chạy được ghi vào `.runtime/logs/results-<group>.json` và `.runtime/logs/state.properties`.

---

## 11. Kết luận

- Toàn bộ **138 luồng happy-path** trên 9 service, gateway và Socket.IO đều trả về thành công với dữ liệu seed thật.
- Các chuỗi nghiệp vụ phức tạp đã được kiểm chứng xuyên service: **đặt tour → giữ chỗ → thanh toán VNPay (IPN/return) → booking CONFIRMED → hóa đơn tự động**; **đổi lịch khách → admin duyệt**; **sự cố lịch khởi hành → đề xuất thay thế → hoàn tiền → kích hoạt**; **hủy giao dịch hết hạn qua đối soát**; **đồng bộ → tìm kiếm tour**; **tải ảnh công khai**; **trợ lý booking AI**.
- Các phần phụ thuộc dịch vụ ngoài (Cloudinary, Groq, MoMo, Google OAuth) được liệt kê rõ ở mục 8 kèm lý do, sẵn sàng bổ sung khi cung cấp credential thật.
