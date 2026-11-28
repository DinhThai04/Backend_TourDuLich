# Báo cáo LaTeX — SGU Tour (Backend + Frontend)

Mã nguồn LaTeX của báo cáo đồ án Chuyên đề J2EE cho hệ thống đặt tour
du lịch: backend `Backend_TourDuLich` (12 ứng dụng Spring Boot, 9 database
vật lý, RabbitMQ outbox/inbox và saga) và frontend
`Frontend_TourDuLich` (React 19 + TypeScript + Vite + Ant Design).

## Yêu cầu

- **XeLaTeX** (khuyến nghị) trên TeX Live / MiKTeX.
- Các gói: `fontspec`, `polyglossia`, `tocloft`, `tikz`, `pgf`,
  `tcolorbox`, `longtable`, `tabularx`, `booktabs`, `listings`,
  `hyperref`, `graphicx`, `enumitem`.
- Font hệ thống: Times New Roman, Arial, Consolas.

## Biên dịch

Từ thư mục `report/`, chạy XeLaTeX 2–3 lần để cập nhật mục lục, danh mục
hình/bảng và tham chiếu chéo:

```bash
xelatex -interaction=nonstopmode main.tex
xelatex -interaction=nonstopmode main.tex
xelatex -interaction=nonstopmode main.tex
```

Kết quả là `main.pdf`.

> Lưu ý: `latexmk` của MiKTeX cần Perl; nếu chưa cài Perl, hãy gọi
> `xelatex` trực tiếp như trên.

## Cấu trúc

```text
report/
  main.tex                 # tài liệu gốc, gộp các chương và hình
  preamble.tex             # gói, font, màu, kiểu TikZ, macro
  chapters/
    00-cover.tex           # bìa (logo images/sgu-logo.png)
    00-frontmatter.tex     # lời cảm ơn, tóm tắt, mục lục, danh mục
    01-gioi-thieu.tex      # Phần I — Giới thiệu
    02-phan-tich.tex       # Phần II — Phân tích và đặc tả
    03-thiet-ke.tex        # Phần III — Thiết kế (gồm thiết kế CSDL)
    04-trien-khai.tex      # Phần IV — Triển khai và giao diện
    05-ket-luan.tex        # Phần V — Kết luận và hướng phát triển
    06-phu-luc.tex         # Phụ lục A — API, cấu hình, lệnh
    07-tai-lieu.tex        # Tài liệu tham khảo
  figures/                 # hình vẽ TikZ (kiến trúc, ERD, tuần tự, ...)
  images/                  # ảnh minh họa (xem images/README.md)
  mockups/                 # nguồn tạo ảnh minh họa (HTML + render.mjs)
```

## Ghi chú

- Báo cáo dùng số La Mã cho chương/mục để khớp với mẫu báo cáo.
- Phần thiết kế cơ sở dữ liệu (`sec:co-so-du-lieu`) đã được hoàn thiện:
  9 database vật lý theo mô hình database-per-service.
- Ảnh minh họa nằm trong `images/`; tên tệp khớp các lệnh `\screenshot`.
  Khi thiếu ảnh, khung giữ chỗ vẫn hiển thị và tài liệu vẫn biên dịch.
- Thư mục `mockups/` chỉ dùng để tái tạo ảnh; có thể bỏ khi sao chép báo cáo.
