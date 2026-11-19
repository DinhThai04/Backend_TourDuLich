# SGU Tour — Cấu hình cục bộ

## Giới thiệu

Thư mục này được đưa lên Git với các file `*.properties.example` và tài liệu hướng dẫn. File mẫu chỉ có tên cấu hình, chú thích và giá trị rỗng. Các file `*.properties` chứa cấu hình để chạy ứng dụng được Git bỏ qua.

## Tạo file cấu hình

Chạy tại thư mục `Backend_TourDuLich` sau khi clone:

```powershell
Get-ChildItem -LiteralPath .local -Filter *.properties.example | ForEach-Object {
    $destination = Join-Path $_.DirectoryName $_.BaseName
    if (-not (Test-Path -LiteralPath $destination)) {
        Copy-Item -LiteralPath $_.FullName -Destination $destination
    }
}
```

Lệnh tạo tám file `.properties` từ mẫu và giữ nguyên file cấu hình đã tồn tại. Đổi file mẫu không tự cập nhật file đang sử dụng; sau khi pull, đối chiếu các cấu hình mới và bổ sung nếu cần.

## Tạo JWT key dùng chung

Sau khi copy mẫu lần đầu, chạy đoạn sau trong cùng terminal. Đoạn lệnh kiểm tra cả tám file có dòng `JWT_SIGNER_KEY=` rỗng trước khi ghi, không ghi đè key đã cấu hình và không in key ra màn hình.

```powershell
$modules = @(
    'auth_service', 'booking_service', 'payment_service',
    'pricing_service', 'tour_service', 'file_service', 'invoice_service', 'search_service'
)
$jwtFiles = @($modules | ForEach-Object { Join-Path .local "$_.properties" })
foreach ($path in $jwtFiles) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Chưa có file cấu hình: $path. Hãy copy mẫu trước."
    }
    $content = Get-Content -LiteralPath $path -Raw
    if ($content -notmatch '(?m)^JWT_SIGNER_KEY=\r?$') {
        throw "JWT key đã được cấu hình hoặc không có dòng rỗng trong $path. Giữ nguyên key hiện tại."
    }
}

$jwtBytes = New-Object byte[] 64
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($jwtBytes) } finally { $rng.Dispose() }
$jwtKey = [Convert]::ToBase64String($jwtBytes)
foreach ($path in $jwtFiles) {
    $content = Get-Content -LiteralPath $path -Raw
    $content = [regex]::Replace($content, '(?m)^JWT_SIGNER_KEY=\r?$', "JWT_SIGNER_KEY=$jwtKey")
    Set-Content -LiteralPath $path -Value $content -Encoding ascii
}
```

Giữ nguyên key giữa các lần khởi động. Đổi key làm token được cấp trước đó không còn hợp lệ. Các máy chạy độc lập có thể dùng key khác nhau; các service trong cùng một hệ thống phải dùng cùng key.

Search service không có datasource nhưng vẫn cần cùng `JWT_SIGNER_KEY` để xác minh token. Gateway và AI service không dùng file JWT riêng trong thư mục này.

## Thay JWT key đang sử dụng

Dừng các service của đồ án, sau đó chạy tại thư mục backend:

```powershell
powershell -NoProfile -File scripts/Rotate-LocalJwt.ps1
```

Script sinh key từ 64 byte ngẫu nhiên, cập nhật đồng bộ tám file cấu hình và không in key hoặc tạo bản sao chứa key cũ. Các cấu hình khác được giữ nguyên. Nếu ghi file thất bại, script khôi phục các file đã thay trong khả năng của filesystem. Có thể chạy với `-WhatIf` để kiểm tra trước khi thay đổi.

Script từ chối chạy khi thiếu file, có nhiều dòng JWT trong một file hoặc có `JWT_SIGNER_KEY` ở environment Process/User/Machine, vì giá trị này có thể ghi đè cấu hình local. Cấu hình riêng trong IDE cũng cần được kiểm tra và bỏ override cũ. Sau khi đổi key, khởi động lại các service cùng nhau và đăng nhập lại.

Credential VNPay, MoMo, Cloudinary, Groq và SMTP phải được thay hoặc thu hồi tại nhà cung cấp; tạo chuỗi ngẫu nhiên cục bộ không thay thế việc này. Không tái sử dụng secret đã lộ cho một tích hợp khác.

## Các giá trị cần điền

| Cấu hình | File | Docker Compose | IDE/Maven |
|---|---|---|---|
| `JWT_SIGNER_KEY` | Cả tám file | Bắt buộc, cùng key | Bắt buộc, cùng key |
| `DB_PASSWORD` | Bảy service có datasource | Compose lấy từ `MYSQL_PASSWORD` trong `.env`, không cần điền ở đây | Theo tài khoản MySQL đang dùng; chỉ để trống nếu MySQL cho phép |
| `DB_URL`, `spring.datasource.username` | Bảy service có datasource | Compose đặt host `mysql`, user `sgu_tour` | Mặc định `localhost:3306/dbtour_v2`, user `root`; bỏ comment và chỉnh nếu khác |
| `VNPAY_HASH_SECRET`, `VNPAY_TMN_CODE` | `payment_service.properties` | Bắt buộc để payment khởi động | Bắt buộc để payment khởi động |
| `CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY`, `CLOUDINARY_API_SECRET` | `file_service.properties` | Cần cấu hình tài khoản ảnh | Cần cấu hình tài khoản ảnh |

VNPay/Cloudinary dùng credential của tài khoản tích hợp do nhóm quản lý; các giá trị rỗng trong mẫu không thể thay thế credential đó. Có thể đặt biến môi trường thay cho file; khi dùng Compose, chỉ các biến được khai báo truyền vào container mới có hiệu lực.

Cấu hình search, AI và mail được mô tả trong README từng module và `docs/PROJECT.md`. Không đặt key AI hoặc Cloudinary trong frontend.

## Quy tắc đưa file lên Git

- Có thể commit: file mẫu không có secret, tài liệu và mã khởi tạo cấu hình.
- Không commit: `.properties` dùng thực tế, `.env`, token hoặc credential nhà cung cấp, kể cả credential sandbox.
- `.gitignore` chỉ tác động tới file chưa được theo dõi. Nếu một secret đã từng được commit, cần thu hồi/thay secret đó và xử lý lịch sử Git; xóa giá trị ở commit sau không xóa nó khỏi lịch sử.

`scripts/check-env.ps1` kiểm tra key chung của tám service và cấu hình VNPay bắt buộc. Script đối chiếu fingerprint SHA-256 với cấu hình hiện hành, từng file `.local/*.properties`, `.env` gốc backend và `ai_service/.env`. Kiểm tra cả file trên đĩa giúp phát hiện giá trị cũ có thể được một cách khởi chạy khác sử dụng. Danh sách fingerprint không chứa giá trị secret.
