# Dữ Liệu Tour Miền Bắc

Bộ mẫu ban đầu gồm 5 tour miền Bắc được tổng hợp từ các trang tour công khai truy cập ngày **2026-10-01**. File Excel gốc không còn kèm trong repo; database demo hiện tại (`data_seed/database_all.sql`) có 15 tour, gồm 5 tour này và các tour bổ sung được chuẩn hóa cùng quy ước. Dữ liệu chỉ phục vụ demo/học tập; không phải dữ liệu thương mại đã được cấp quyền sử dụng.

## Quy ước dữ liệu

| Nhãn | Ý nghĩa |
|---|---|
| `WEB` | Lấy trực tiếp từ trang nguồn: tên, giá được công bố, thời lượng, hành trình hoặc lịch trình. |
| `WEB_TOM_TAT` | Lịch trình được tóm tắt/paraphrase từ trang nguồn, không sao chép nguyên văn. |
| `ANH_XA` | Dữ liệu web được chuẩn hóa để khớp entity của đồ án. Ví dụ nguồn ghi "1-4 tuổi miễn phí" nhưng entity có `toddlerPrice` và `infantPrice`, nên map thành `0`. |
| `NOI_BO` | Được tạo cho đồ án: mã tour, mã đợt đi, số ghế, ngày đi cụ thể, mã xe, mã ảnh. |
| `GIA_NOI_BO` | Giá do đồ án ước tính vì nguồn không công khai giá. Không được trình bày là giá của nhà cung cấp. |

## Năm tour và nguồn

| Mã nội bộ | Tour | Nguồn | Dữ liệu web chính |
|---|---|---|---|
| `MB-SAPA-3N2D-01` | Hà Nội - Sapa - Fansipan - Cát Cát - Hàm Rồng 3N2Đ | [Van Tours](https://vantours.com.vn/san-pham/tour-sapa-cat-cat-ham-rong-fansipan-3-ngay-2-dem/) | 3N2Đ, ô tô, giá 3.550.000đ, Fansipan/Cát Cát/Hàm Rồng. |
| `MB-NB-2N1D-01` | Hà Nội - Hoa Lư - Tam Cốc - Hang Múa - Bái Đính - Tràng An 2N1Đ | [PAS Travel](https://pastravel.vn/product/tour-ninh-binh-2n1d-02/) | 2N1Đ, ô tô, khởi hành hằng ngày, giá 2.050.000đ. |
| `MB-MC-3N2D-01` | Hà Nội - Mộc Châu 3N2Đ | [Tavivu Travel](https://tavivutravel.com/st_tour/ha-noi-moc-chau-3n2d/) | 3N2Đ, ô tô 45 chỗ, giá 2.150.000đ, giá trẻ em 1.075.000đ. |
| `MB-BAVI-2N1D-01` | Hà Nội - Khoang Xanh - Suối Tiên - Ba Vì 2N1Đ | [Du lịch Tầm Nhìn Việt](https://www.dulichvtv.com/du-lich-ha-noi-ha-noi-khoang-xanh-suoi-tien-2-ngay/) | 2N1Đ, ô tô, tour theo yêu cầu, lịch trình và chính sách trẻ em 50%. Không công khai giá. |
| `MB-HL-YT-3N2D-01` | Hà Nội - Ninh Bình - Hạ Long - Yên Tử 3N2Đ | [Du lịch Tầm Nhìn Việt](https://www.dulichvtv.com/tour-du-lich-ha-noi-ninh-binh-ha-long-yen-tu-3-ngay-2-dem/) | 3N2Đ, ô tô, thứ 5 hằng tuần, 20-30 khách, giá 3.350.000đ. |

## Phần do đồ án tạo

- `tourProgram`, `tourCode`, `tourPriceId`, `vehicleId`, `imageIds`: mã nội bộ theo quy ước dễ đọc.
- `departureDate`, `returnDate`, `availableSeats`: lịch demo tháng 10/2026. Chúng **không phải** lịch/ghế của nhà cung cấp.
- `singleSupplementPrice`: không có trong 4/5 nguồn nên là số demo. Riêng Ninh Bình nguồn không công bố phụ thu cho tour PAS Travel, nên cũng là số demo.
- Giá người lớn của tour Khoang Xanh - Suối Tiên là `1.350.000đ`: ước tính nội bộ vì trang nguồn không hiển thị giá.
- Dữ liệu giá trẻ em/toddler/infant được chuẩn hóa theo entity `TourPrice`. Nơi nguồn chỉ ghi một nhóm tuổi miễn phí thì `toddlerPrice` và `infantPrice` được gán `0`.

## Ảnh và bản quyền

Bộ mẫu ban đầu có sheet `IMAGE_SOURCE` giữ URL ảnh công khai tại trang nguồn. Entity `Tour.imageIds` cần **ID file_service**, không dùng trực tiếp URL ngoài. Khi import:

1. Chỉ sử dụng ảnh khi có quyền sử dụng hoặc thay bằng ảnh tự chụp/ảnh có license phù hợp.
2. Upload ảnh hợp lệ qua `file_service`.
3. Thay các mã tạm `IMG-...` trong sheet `TOUR` bằng ID thật trả về từ file_service.

## Cảnh báo cập nhật

Giá, lịch khởi hành, số chỗ, điều kiện trẻ em và điểm tham quan của tour có thể thay đổi. Trước khi demo hoặc đưa vào báo cáo, mở lại URL nguồn để đối chiếu. Không dùng dữ liệu này để nhận đặt tour hoặc công bố như dữ liệu chính thức của các đơn vị nguồn.
