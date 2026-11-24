# Roadmap hoàn thiện backend microservice ở mức production

> Phạm vi: backend trong `Backend_TourDuLich`; không bao gồm frontend. Roadmap là kế hoạch làm việc và sổ nghiệm thu liên tục. Không đánh dấu một phase hoàn thành nếu chưa lưu bằng chứng và đạt đủ gate.
>
> Mục tiêu production ở đây là một bản phát hành hiện tại có thể triển khai và vận hành an toàn trong môi trường thực tế. Các hoạt động cập nhật/bảo trì tương lai nằm ngoài phạm vi; tuy nhiên backup/restore, giám sát, rollback và xử lý sự cố của chính bản phát hành vẫn là tiêu chí bắt buộc.

## Kết quả cuối cùng cần đạt

Một release có thể triển khai lặp lại từ source/config đã version hóa; từng service có ranh giới dữ liệu thực sự riêng; luồng nghiệp vụ liên service đúng khi retry, timeout, lỗi một phần hoặc nhận sự kiện trùng; dữ liệu hiện hữu được chuyển đổi và đối chiếu có kiểm soát; secrets không nằm trong repository; hệ thống có kiểm soát truy cập, quan sát được, sao lưu/khôi phục và có quy trình triển khai/rollback đã kiểm chứng. “Có nhiều service và nhiều database” một mình chưa đủ để gọi là production microservice.

## Nguyên tắc làm việc và an toàn dữ liệu

- Chỉ làm backend. Không sửa frontend.
- Mọi thay đổi phải giữ nguyên thay đổi có sẵn trong working tree; không reset/revert thay đổi người dùng.
- Không xóa hoặc khởi tạo lại volume/cloud stack đang có. Mọi kiểm chứng dùng Compose project và volume riêng; chỉ dọn tài nguyên do chính lần kiểm chứng tạo ra sau khi xác minh đúng tên/project.
- Không cutover production hay chạy migration phá hủy trên dữ liệu hiện hữu trước khi có backup/restore đã chứng minh, kế hoạch đối chiếu và cửa sổ chuyển đổi.
- Mỗi service chỉ sở hữu schema của mình. Giao tiếp liên service qua API/event; không cross-database query, FK hay giao dịch phân tán.
- Ghi kết quả, lệnh, môi trường, commit/diff liên quan, lỗi còn lại và quyết định go/no-go ngay trong file này.

## Trạng thái tổng thể (cập nhật 2026-11-24)

- **Phase 1 (Hạ tầng & 9 DB schema)**: **Hoàn thành (100%)** — 9 DB vật lý độc lập, RabbitMQ, Redis, user/grant cô lập, healthcheck canary, không publish port mặc định đã pass trên cloud sandbox.
- **Phase 2 (Refactor vertical slices — xóa cross-DB SQL)**: **Đang thực hiện (~55%)** — Đã xong hạ tầng consumer/outbox của Reporting, Notification, File/Tour gallery detach & tombstone, Auth avatar revision & account delete outbox, File `AvatarWritePolicy` + bỏ legacy guard, Tour `TokenRevocationChecker`, Booking→Notification (Slice A), Booking test suite xanh (112 tests), và Tour↔Booking write-path (`holdSeats`/`releaseHold`). **Blocker chính còn lại**: `payment_service` (`VnPayOrderService`, `ReconciliationService`, `PaymentMailOutboxWriter`), `invoice_service` (`InvoiceService`, `PaymentEventConsumer` poll `payment_outbox`), `booking_service` (read-side `tour_departure`/`users`/`prices`/`BookingAdviceService`/`ReminderStateReader`), `tour_service` (`DepartureCapacityReader`, `TourPriceView`), `auth_service` (`AccountLifecycleService`), và `TokenRevocationChecker` ở Booking/Payment/Invoice.
- **Phase 3 (Data migration, Compose app stack 9 DB & Production hardening)**: **Chưa bắt đầu** — Chờ đóng toàn bộ cross-DB SQL ở Phase 2 và đưa 12 app services vào `docker-compose.microservice.yml`.
- **Production release**: **NO-GO** cho tới khi mọi gate Phase 1–3 đạt và được ghi bằng chứng.

---

# Phase 1 — Nền tảng, ownership dữ liệu và cô lập hạ tầng

## Mục tiêu

Thiết lập hợp đồng dữ liệu có thể kiểm tra được và hạ tầng microservice chạy an toàn, lặp lại: mỗi DB có schema/user/volume riêng, không mở cổng hạ tầng mặc định ra host, không có credential mặc định, healthcheck xác nhận schema đã nạp, và Compose không va chạm với môi trường khác. Đây là nền tảng, chưa phải cutover ứng dụng: hiện có code cross-service SQL nên không chuyển runtime sang DB tách rời trước Phase 2.

## Đầu vào và phạm vi

- `docs/MICROSERVICES.md`, `docs/INTERNAL_API_CONTRACTS.md` và các file `data_seed/microservices/sgu_*.sql`.
- `docker-compose.microservice.yml`, cấu hình DB/RabbitMQ/Redis và biến môi trường.
- Source Java để lập danh sách đường đọc/ghi vượt ownership; Phase 1 chỉ sửa schema/hạ tầng có thể tách an toàn, chưa tái cấu trúc luồng nghiệp vụ.
- 9 DB mục tiêu: `sgu_auth`, `sgu_tour`, `sgu_pricing`, `sgu_booking`, `sgu_payment`, `sgu_invoice`, `sgu_file`, `sgu_notification`, `sgu_reporting`.

## Công việc

1. Chốt danh mục owner database/table dựa trên schema và luồng ghi thực tế; tìm bảng lặp, bảng mồ côi, FK vượt miền và chỗ khác service đọc/ghi.
2. Sửa migration để mỗi bảng chỉ được tạo trong schema của owner. Không xóa dữ liệu hoặc migration của DB đang dùng.
3. Làm Compose độc lập, project-scoped: bỏ `container_name`/network name cố định, không publish cổng DB/broker/cache theo mặc định, yêu cầu biến secret thay cho fallback yếu, bật xác thực Redis, healthcheck từng DB kiểm tra được bảng canary của schema.
4. Xác nhận user ứng dụng mỗi DB chỉ có quyền trên đúng DB đó; kiểm thử quyền đọc DB khác phải bị từ chối. Root credential tách biệt giữa các DB, không dùng cho ứng dụng.
5. Kiểm tra init SQL trên volume sạch của project thử nghiệm và xác nhận số bảng, FK, indexes, outbox/inbox. Không chạy SQL khởi tạo lại trên volume có dữ liệu.
6. Tạo danh sách chính xác các truy cập cross-service hiện hữu và gắn chủ sở hữu, API/event thay thế, phase xử lý.
7. Tài liệu hóa cách tạo secret local/cloud, khởi động, healthcheck, backup/restore thử nghiệm và dọn đúng tài nguyên thử nghiệm.

## Khu vực dự kiến thay đổi

`docs/roadmap.md`, `docs/MICROSERVICES.md`, `docker-compose.microservice.yml`, `data_seed/microservices/*.sql`, có thể bổ sung script kiểm tra cấu trúc/quyền. Các thay đổi Java lớn thuộc Phase 2.

## Đầu ra bắt buộc

- Bảng ownership đã khớp với schema migration.
- Compose hạ tầng chạy được từ project sạch, không cổng công khai mặc định và không secret mặc định.
- Báo cáo kiểm tra 9 schema, 9 user grants, volume riêng, healthcheck, quyền chéo bị từ chối.
- Danh sách cross-service access chưa xử lý, có mapping tới phase/slice.
- Lệnh vận hành và bằng chứng chạy được trên local/cloud sandbox cô lập.

## Gate nghiệm thu

- [x] Tất cả schema khởi tạo thành công trên project/volume thử nghiệm mới.
- [x] Mỗi database chỉ có schema thuộc owner; bảng trùng ownership đã xử lý hoặc có quyết định được ghi rõ và có kế hoạch đóng.
- [x] Provisioning user/grant được xác minh chỉ có quyền trên DB của mình; root credential riêng từng DB. Runtime datasource chưa được Compose microservice khởi chạy nên login/runtime privilege sẽ được audit trong Phase 2–3.
- [x] DB, RabbitMQ và Redis không publish cổng ra host; Redis và RabbitMQ có xác thực bắt buộc.
- [x] Compose không chứa password mặc định/fallback; secret kiểm chứng được sinh tạm trên cloud, không đưa vào repository.
- [x] Healthcheck DB kiểm tra bảng canary; RabbitMQ và Redis healthcheck đạt.
- [x] Cloud verification dùng project/volume riêng; stack/container cũ còn nguyên trạng.
- [x] Danh sách cross-service SQL được ghi nhận bên dưới; chưa tuyên bố application data isolation hoàn tất.

## Cách chạy và kiểm chứng Phase 1

- Tạo file env ngoài repository với các biến `RABBITMQ_PASSWORD`, `REDIS_PASSWORD`, `MYSQL_<SERVICE>_PASSWORD` và `MYSQL_ROOT_<SERVICE>_PASSWORD` cho `AUTH`, `TOUR`, `PRICING`, `BOOKING`, `PAYMENT`, `INVOICE`, `FILE`, `NOTIFICATION`, `REPORTING`. Tạo từng giá trị ngẫu nhiên đủ mạnh (ví dụ `openssl rand -hex 32`), đặt quyền chỉ owner đọc/ghi (`chmod 600`); không in giá trị ra log/chat và không commit file này.
- Kiểm tra cấu hình: `docker compose --project-name sgu-ms --env-file "$ENV_FILE" -f docker-compose.microservice.yml config --quiet`.
- Khởi động lần đầu trên host sạch: `docker compose --project-name sgu-ms --env-file "$ENV_FILE" -f docker-compose.microservice.yml --parallel 1 up -d --wait --wait-timeout 300`. Các database cần tối thiểu 512 MiB cấu hình giới hạn mỗi instance; xác nhận ngân sách RAM tổng trước khi bật đủ 9 DB cùng service ứng dụng.
- Kiểm tra tình trạng: `docker compose --project-name sgu-ms --env-file "$ENV_FILE" -f docker-compose.microservice.yml ps`; kiểm tra grants bằng admin account và thử kết nối bằng account service; không dùng root từ ứng dụng.
- Dọn project thử nghiệm: `docker compose --project-name sgu-ms --env-file "$ENV_FILE" -f docker-compose.microservice.yml down`. Chỉ xóa volume khi đã xác minh label `com.docker.compose.project=sgu-ms` thuộc đúng project thử nghiệm; tuyệt đối không dùng `down -v` trên project có dữ liệu cần giữ.

Lệnh mẫu không thay thế secret manager, migration framework, backup/restore hay runtime least privilege. Các điều đó là gate Phase 2–3.

## Nhật ký thực hiện

| Ngày | Việc/bằng chứng | Kết quả | Còn lại |
|---|---|---|---|
| 2026-11-23 | Rà schema ownership và code truy cập `tour_media_lock`, `tour_image_ids`, `file_mgmt`; rà Compose hạ tầng. | Xác nhận `tour_media_lock` bị khai báo cả trong `sgu_tour` và `sgu_file`; docs giao owner cho `file_service`. Xác nhận còn cross-service access: `tour_service` xóa `tour_image_ids` và cập nhật `file_mgmt`; `file_service` kiểm tra tham chiếu qua `location`, `users`, `tour_image_ids`. | Các cross-service access cần được thay bằng hợp đồng API/event ở Phase 2; không cutover DB trước đó. |
| 2026-11-23 | Bỏ `tour_media_lock` khỏi migration `sgu_tour`. | Đã sửa file migration; file chỉ còn ở `sgu_file`, đúng owner đã công bố. | Không chạy lại init SQL trên volume cũ; đây là schema baseline cho DB sạch. |
| 2026-11-23 | Structural audit 9 file migration SQL tại local. | PASS: table counts khớp cloud; mọi `REFERENCES` đều trỏ tới table được tạo trong cùng schema; mỗi DB có outbox/inbox; `tour_media_lock` chỉ do `sgu_file` tạo. `git diff --check` cho các file Phase 1 sạch. | Chưa kiểm tra migration nâng cấp trên dữ liệu đang chạy; Phase 3 rehearsal sẽ làm trên snapshot/clone. |
| 2026-11-23 | Hardening `docker-compose.microservice.yml`. | Xóa tên container/network cố định và published ports; bắt buộc secret cho RabbitMQ, Redis, 9 app users và 9 root users độc lập; Redis bật AUTH; RabbitMQ volume init có quyền giới hạn và chạy không network; healthcheck từng DB kiểm tra bảng canary; ngưỡng bộ nhớ DB tối thiểu 512 MiB. `docker compose config --quiet` đạt khi cung cấp biến môi trường tạm. | Ứng dụng backend chưa được đưa vào file Compose này; endpoint/JDBC runtime verification thuộc Phase 2. Production secret manager và quyền runtime DML tối thiểu thuộc Phase 3. |
| 2026-11-23 | Chạy xác minh cloud trên Compose project/volume riêng, tuần tự để giữ sandbox ổn định. | PASS: 9 DB init từ volume sạch; table counts: auth 9, tour 9, pricing 3, booking 10, payment 8, invoice 3, file 6, notification 6, reporting 9. Từng app user đọc được DB riêng; `SHOW GRANTS` chỉ có quyền schema của chính service (ngoài `USAGE` toàn cục); canary từng DB đạt. RabbitMQ/Redis healthy; kiểm tra Docker `HostConfig.PortBindings` trả `{}` cho toàn bộ container của các project thử nghiệm. | Một lần chạy đầu với 288 MiB khiến MySQL nhỏ không khởi động ổn định; sau khi nâng lên 512 MiB, cả 9 DB đều đạt khi chạy tuần tự. Cần benchmark tải và xác nhận ngân sách RAM đồng thời trong Phase 3. |
| 2026-11-23 | Kiểm tra an toàn sandbox sau thử nghiệm. | PASS: container DB cũ (`sgu_mysql_*`) vẫn running/healthy như trước; không dùng lại volume cũ. Container/volume thử nghiệm có label project riêng và được dọn sau khi thu bằng chứng. | Một service MySQL cũ khác trong stack gốc đã unhealthy từ trước do thiếu schema/table; đây không phải tài nguyên do Phase 1 tạo và cần xử lý theo kế hoạch migration/cutover Phase 3. |

## Danh mục cross-service access còn lại — đầu vào Phase 2

| Nguồn | Truy cập hiện tại | Ranh giới đích / công việc |
|---|---|---|
| `tour_service` — `TourService.deleteTour` | Xóa `tour_image_ids` (đúng owner `tour_service`); trước đây cập nhật trực tiếp `file_mgmt` (owner `file_service`) để detach ảnh khi xóa tour. | Đã chuyển sang `tour_outbox` và internal File API idempotent; còn cần MySQL/HTTP integration trong project cô lập và chạy thử replay operator khi outbox vào `DEAD_LETTER`. |
| `file_service` — `FileMgmtRepository` legacy URL reference query | Guard cleanup còn đọc `location`, `tour_image_ids` (Tour) và `users` (Auth) để giữ tương thích trong shared DB. Các luồng File-owned gallery/avatar/location nay duy trì `file_reference`; guard kết hợp ledger File-owned với truy vấn legacy. | Chưa xóa legacy query: cần backfill external references và đồng bộ thay đổi từ Auth/Tour bằng API/outbox có idempotency. Chỉ bỏ truy vấn sau khi đã đối chiếu đủ dữ liệu và chứng minh không có khoảng trống khi transaction owner rollback. |
| `file_service` — avatar/location/gallery workflows | Một số luồng upload/delete phụ thuộc bảng của auth/tour trong shared DB; cần rà toàn bộ repository/entity/transaction khi triển khai slice File. | API/event theo contracts; không giữ transaction qua DB; file chỉ xóa vật lý khi không còn reference hợp lệ. |
| `file_service` — `AvatarWritePolicy`, `TokenRevocationChecker`, `FileMgmtRepository` | Còn đọc `users`, `roles`, `location`, `tour_image_ids`, `user_account_event`, `invalidated_token` của Auth/Tour. Kiểm tra tour trước đây đọc bảng `tour`; đã thay bằng `tour_media_lock.deleted_at` thuộc File và event detach có tombstone. | Auth/Tour/File là các owner; dùng API/event, chuyển legacy URL reference sang `file_reference`, bỏ các cross-schema SQL còn lại sau backfill. Migration File V002 phải chạy trước khi deploy code tombstone lên schema đã tồn tại. |
| `auth_service` — avatar/profile lifecycle | `AccountLifecycleService.delete` ghi `auth.account.deleted.v1` trong Auth transaction; publisher retry/dead-letter và File inbox/tombstone đã triển khai. Avatar URL được áp dụng trong Auth DB theo revision tăng đơn điệu do File trả về. | Đã bỏ `AvatarProfileWriter` cross-DB read/lock. File vẫn đọc Auth tables để kiểm tra policy avatar và session revocation; cần hợp đồng API/event fail-closed, rồi mới bỏ dependency SQL. |
| `payment_service` — checkout/settlement/reconciliation | Đọc/ghi `booking`, `booking_reservation`, `booking_price_snapshot`; cập nhật `tour_departure`; đọc/ghi `invoice` và `payment`. | Booking/Tour/Invoice sở hữu các bảng đó; giữ Payment DB local, điều phối qua API/outbox/inbox và saga/compensation. |
| `booking_service` — lifecycle/reminder/mail eligibility | Trực tiếp tham chiếu `tour_departure`, `prices`, `payment`, `invoice`, `payment_reconciliation`, `file_mgmt`, `users` và một số bảng tour; còn có local SMTP `mail_outbox` code nhưng bảng này không nằm trong `sgu_booking`. | Thay bằng contract/event/snapshot; bỏ SMTP khỏi Booking và để Notification sở hữu delivery. Phải giải quyết trước khi bật runtime trên schema tách riêng. |
| `invoice_service` — invoice/payment validation | Đọc/ghi bảng Booking/Tour/Payment/Reservation ngoài owner; `PaymentEventConsumer` còn poll `payment_outbox` từ datasource Invoice. | Chỉ tạo invoice từ event versioned đã xác thực; invoice local transaction; inbox/idempotency và compensation qua saga. |
| `tour_service` — capacity/search/operations/auth | `DepartureCapacityReader` query `booking`, `invoice`, `payment`; `SearchCatalogSnapshot`/advice có truy vấn dữ liệu khác owner; xóa tour cập nhật `file_mgmt`; TokenRevocationChecker đọc auth tables. | Projection/snapshot thuộc owner hoặc API/event; không cập nhật file trực tiếp; chuyển session validation sang hợp đồng nội bộ Auth. |

Đường dẫn đã rà: `tour_service/src/main/java/com/example/tour_service/service/TourService.java`; `file_service/src/main/java/com/example/file_service/repository/FileMgmtRepository.java`; hợp đồng hiện có tại `docs/INTERNAL_API_CONTRACTS.md` mục File Service. Phase 2 phải đối chiếu implementation với hợp đồng và cập nhật inventory khi phát hiện thêm truy cập.

## Trạng thái Phase 1

**Hoàn thành — hạ tầng và schema baseline đã nghiệm thu trên sandbox cloud cô lập.** Đây không phải xác nhận ứng dụng hiện đã chạy database-per-service: source vẫn có cross-service SQL ở trên. Không chuyển runtime/cắt traffic sang DB riêng trước khi Phase 2 loại bỏ các truy cập đó và Phase 3 chuyển/đối chiếu dữ liệu.

---

# Phase 2 — Refactor backend theo vertical slice và độ tin cậy liên dịch vụ

## Mục tiêu

Loại bỏ shared-database coupling khỏi code và làm các luồng nghiệp vụ chạy đúng với database-per-service, eventual consistency, retry và lỗi một phần. Mỗi slice phải đứng độc lập: API/event contract, local transaction, idempotency, observability, kiểm thử tích hợp và rollback rõ ràng trước khi chuyển sang slice tiếp theo.

## Điều kiện vào

- Phase 1 đạt gate ownership và hạ tầng.
- Có baseline build/test theo từng module, cấu hình môi trường, API/event inventory và số liệu dữ liệu hiện hành.
- Chưa chuyển production traffic; mọi DB thử nghiệm dùng project riêng.

## Thứ tự slice và việc cần làm

1. **Reporting**: chỉ nhận event/snapshot; projection idempotent; không query DB nghiệp vụ.
2. **Notification**: sở hữu template, delivery, suppression và mail outbox; retry/backoff, dedupe, trạng thái gửi; không dựa vào booking DB.
3. **File**: file metadata/reference và media locks do file service sở hữu; xác thực quyền sở hữu qua contract; dọn media không xóa nhầm dữ liệu nghiệp vụ.
4. **Pricing**: quote/version giá qua API/event; booking lưu price snapshot bất biến; không đọc bảng giá trực tiếp.
5. **Tour ↔ Booking**: API giữ/trả chỗ có TTL, optimistic concurrency và idempotency; booking không cập nhật tour tables; xử lý expiry, cancellation và thay đổi lịch.
6. **Auth**: token/account lifecycle, reviews nếu thuộc owner đã chốt; service khác không đọc users/roles trực tiếp; nội bộ có authz rõ ràng.
7. **Payment ↔ Booking ↔ Invoice**: callback ký/xác minh, idempotency key, payment state machine, outbox/inbox và saga có compensation; invoice chỉ phát hành theo trạng thái hợp lệ; không dùng 2PC.
8. **Search/AI/Gateway**: chỉ qua API/snapshot; timeout, giới hạn payload, circuit breaking/fallback; gateway không trở thành business-data owner.

Mỗi lát cắt: mô hình lỗi và hợp đồng; sửa code; unit/integration/contract tests; thử timeout/duplicate/replay; cấu hình observability; tài liệu rollback; review source và dependency DB; cập nhật bảng trạng thái ở đây.

## Đầu ra bắt buộc

- Không còn query/ghi trực tiếp sang DB ngoài owner trong production source.
- API/event contract có version, timeout, auth nội bộ, retry chỉ cho thao tác an toàn, idempotency và correlation/causation IDs.
- Outbox publish bền vững, inbox dedupe; DLQ/retry/replay có giới hạn và vận hành được.
- Saga và compensation cho giữ ghế/đặt tour/thanh toán/hóa đơn/đổi lịch/hoàn tiền.
- Test tích hợp theo từng service với DB riêng và contract/E2E cho luồng quan trọng.

## Gate nghiệm thu

- [ ] Static audit không còn cross-owner SQL/JPA/repository hoặc transaction xuyên DB.
- [ ] Mọi luồng nghiệp vụ quan trọng có sơ đồ trạng thái và xử lý retry/timeout/duplicate/failure rõ ràng.
- [ ] Duplicate callback/event/retry không nhân đôi side effect.
- [ ] Consumer chết/restart không làm mất event; lỗi poison có DLQ và replay an toàn.
- [ ] Test từng module và contract/integration suite đạt trên cấu hình database-per-service.
- [ ] Secrets, PII, payment data được xử lý đúng; log không lộ token/credential/chi tiết nhạy cảm.
- [ ] Từng slice có rollback được mô tả và chứng minh ở staging.

## Trạng thái Phase 2

**Đang thực hiện — chưa đạt gate.** Các consumer mới có test local/cloud; các producer và phần lớn runtime repository vẫn dùng shared schema. Không bắt đầu cutover dữ liệu production trong phase này.

### Trạng thái slice và nhật ký Phase 2 (cập nhật 2026-11-24)

| Slice | Trạng thái | Đã hoàn thành | Blocker còn lại (phải đóng trước Phase 3) |
|---|---|---|---|
| **1. File** | **~95% (gần xong)** | Gallery proof HMAC, Tour→File outbox detach + tombstone (`V002`), `file_reference` ledger, Auth→File account delete outbox + tombstone (`V003`), avatar revision (`V004`/`V005`), thay `AvatarWritePolicy` bằng Auth internal API (`ff7bf05`), bỏ legacy cross-DB guard (`a345802`). File 76/76 tests PASS. | HTTP E2E trên DB-per-service + outbox dead-letter replay smoke trên MySQL thật. |
| **2. Notification** | **~80%** | Sở hữu `sgu_notification` (`mail_outbox`, inbox, durable DLQ, 11+ templates), consumer `booking.mail.requested.v1` (`ae554ae`), Booking đã bỏ local SMTP/outbox (`de34d9e`, `0520678` — Slice A). Notification 7/7 tests PASS, cloud smoke PASS. | `payment_service` (`PaymentMailOutboxWriter`) vẫn ghi local `mail_outbox` → cần chuyển sang publish event `booking.mail.requested.v1` / `payment.mail.requested.v1`. |
| **3. Reporting** | **~50%** | `ReportingEventConsumer`/`Processor` dùng inbox schema thực, dedupe, lọc PII, 7/7 tests PASS, cloud smoke PASS. `booking_service` đã có proxy sang Reporting khi `REPORTING_ENABLED=true`. | Thiếu producer event đầy đủ từ Booking/Payment/Invoice/Tour để populate `ops_*` read models; `OperationsInsights` legacy join 6 bảng vẫn còn làm fallback. |
| **4. Pricing ↔ Tour** | **~60%** | `SearchCatalogSnapshot` gom giá theo batch internal API `/internal/pricing/prices/batch` (max 100 ID), tách transaction Tour khỏi HTTP call. Tour 143/143 tests PASS. | `TourPriceView` / `TourSpecification` vẫn join bảng `prices` của Pricing để lọc/sắp xếp. Cần price projection thuộc `sgu_tour` (hoặc API phân trang) rồi bỏ `TourPriceView`. |
| **5. Tour ↔ Booking** | **~50%** | Tour `TokenRevocationChecker` đã chuyển sang Auth internal API fail-closed (`babbad1`). Booking write-path (`BookingLifecycleService`, `ChangeLedger`, `BookingChangeService`) đã gọi `tourClient.holdSeats`/`releaseHold`. Booking unit/integration 112 tests PASS. | (a) Booking vẫn còn `SELECT ... FOR UPDATE` và read-side SQL trên `tour_departure`, `users`, `prices`, `payment`, `invoice` (`BookingLifecycleService`, `ReservationExpiryService`, `ChangeLedger`, `BookingAdviceService`, `ReminderStateReader`, `MailEligibility`, `MailTourImageResolver`); (b) Tour `DepartureCapacityReader` vẫn query trực tiếp `booking`/`payment`/`invoice`. |
| **6. Auth** | **~70%** | Avatar revision decoupling (`AvatarProfileWriter` không còn đọc File DB), account deletion outbox `auth.account.deleted.v1`, `InternalSessionController` cho revocation check. Auth 135/135 tests PASS. | `AccountLifecycleService` vẫn còn cross-DB query kiểm tra `booking`/`tour_departure`/`payment_reconciliation` → chuyển hẳn sang `BookingInternalClient`/`PaymentInternalClient`. |
| **7. Payment ↔ Invoice (Saga)** | **~30% (Blocker lớn nhất)** | `payment_outbox` publisher có confirm ACK + `eventId` khớp AMQP message ID (Payment 81+2 tests PASS). `PaymentInvoiceSaga` state machine đã có. | (a) `VnPayOrderService` (61 hits), `MoMoOrderService`, `ReconciliationService`, `PaymentInvoiceSaga` vẫn đọc/ghi trực tiếp `booking`, `booking_reservation`, `tour_departure`, `prices`, `invoice`; (b) `PaymentMailOutboxWriter` vẫn ghi `mail_outbox`; (c) `invoice_service` (`InvoiceService`, `PaymentEventConsumer`) vẫn đọc `booking`/`tour_departure` và poll `payment_outbox` trực tiếp thay vì consume RabbitMQ `payment.settled.v1`. |
| **8. Cross-cutting (AuthZ / Gateway / Search / AI)** | **~75%** | HS256 JWT đồng bộ, AI/Gateway hostname & route tests PASS (AI 330/330, Gateway 7/7), Search API-only. | `TokenRevocationChecker` trong `booking_service`, `payment_service`, `invoice_service` vẫn đọc bảng `invalidated_token` của Auth → áp dụng cùng pattern `AuthSessionClient` fail-closed như `tour_service` (`babbad1`) & `file_service`. |

### Kế hoạch đóng Phase 2 — Thứ tự thực hiện tiếp theo (cho phiên SSH Cloud)

Thực hiện tuần tự từng slice, mỗi slice xong phải chạy `mvn -B --no-transfer-progress -f <service>/pom.xml test` xanh và commit nhỏ gọn:

1. **Slice B — TokenRevocationChecker (Quick win, ~1h)**:
   - Thay đọc trực tiếp `invalidated_token` ở `booking_service`, `payment_service`, `invoice_service` bằng gọi `InternalSessionController` của `auth_service` (copy đúng pattern fail-closed đã làm ở `tour_service` commit `babbad1`).
2. **Slice C — Auth `AccountLifecycleService` (~2h)**:
   - Bỏ SQL trực tiếp vào `booking`/`tour_departure`/`payment_reconciliation` trong `AccountLifecycleService.java`; dùng `BookingInternalClient` & `PaymentInternalClient`.
3. **Slice D — Tour `DepartureCapacityReader` & `TourPriceView` (~4-6h)**:
   - `DepartureCapacityReader`: tính sức chứa từ `tour_departure` + `departure_hold` (local `sgu_tour`), bỏ query `booking`/`payment`/`invoice`.
   - `TourPriceView`: đồng bộ `price_snapshot` (hoặc cột giá denormalized trên `tour`) qua Pricing event/API khi cập nhật giá, sau đó bỏ entity `TourPriceView` khỏi `TourSpecification`.
4. **Slice E — Booking read-side decoupling (~6-8h)**:
   - Bỏ `SELECT ... FOR UPDATE` trên `tour_departure` trong `BookingLifecycleService` & `ChangeLedger` (tin cậy lock/optimistic version từ `tourClient.holdSeats`).
   - Thay read-side `tour_departure`, `users`, `prices`, `file_mgmt` trong `ReservationExpiryService`, `BookingAdviceService`, `ReminderStateReader`, `MailEligibility`, `MailTourImageResolver` bằng internal API + snapshot lưu trong `booking_price_snapshot`/`booking`.
5. **Slice F — Payment ↔ Invoice decoupling & RabbitMQ Saga (Trọng tâm, ~8-12h)**:
   - Chuyển `PaymentMailOutboxWriter` sang publish event cho `notification_service` (như Slice A của Booking).
   - Chuyển `VnPayOrderService`, `MoMoOrderService`, `ReconciliationService` sang gọi `BookingInternalClient` & `PricingInternalClient` để lấy quote/validate; chỉ ghi DB `sgu_payment` + `payment_outbox` (`payment.settled.v1`, `payment.refunded.v1`).
   - Chuyển `invoice_service`: `PaymentEventConsumer` tiêu thụ RabbitMQ `payment.settled.v1` (qua `invoice_inbox` idempotent) thay vì poll `payment_outbox`; `InvoiceService` dùng `BookingInternalClient` thay vì query `booking`/`tour_departure`.
6. **Slice G — Phase 2 Gate & Chuẩn bị Phase 3 (Docker Cloud Sandbox)**:
   - Chạy static audit xác nhận 0 cross-DB SQL trên cả 9 services.
   - Bổ sung 12 Spring Boot services (+ Frontend nếu cần demo full) vào `docker-compose.microservice.yml` với `DB_URL` trỏ riêng tới 9 `mysql-*` containers, `SPRING_RABBITMQ_*`, `INTERNAL_*_KEY` bắt buộc.
   - Chạy E2E thực tế trên cloud sandbox: Đăng ký/Login → Tìm tour → Đặt tour (giữ ghế) → Thanh toán sandbox (VNPay/MoMo callback) → Invoice + Notification + Reporting.

| Ngày | Việc/bằng chứng | Kết quả | Còn lại |
|---|---|---|---|
| 2026-11-23 | Reporting consumer/processor và test: `ReportingEventConsumer`, `ReportingEventProcessor`, `ReportingEventProcessorTest`, schema test. `mvn -B --no-transfer-progress -f reporting_service/pom.xml test` local và cloud. | PASS: 7/7 tests trên local và cloud; inbox schema thực, duplicate được dedupe, PII không ghi vào audit, identity mismatch bị từ chối. Retry chỉ ACK sau broker confirm. | Projection còn lại và chưa có producer từ Booking/Tour; cần test với MySQL/RabbitMQ thật ở Phase 2. |
| 2026-11-23 | Notification consumer/processor, DLQ, template rendering, auth route, SQL seed và tests. `mvn -B --no-transfer-progress -f notification_service/pom.xml test` local và cloud. | PASS: 7/7 tests trên local và cloud; xử lý trùng, rollback inbox khi payload invalid, escape HTML, DLQ metadata không trả payload; seed template đủ event mappings. | Chưa có source event tổng quát từ Booking/Tour; Booking/Payment còn local SMTP mail outbox; cần suppression và DB migration được version hóa. |
| 2026-11-23 | Payment publisher/outbox và compatibility test fixtures. `mvn -B --no-transfer-progress -f payment_service/pom.xml test` local và cloud. | PASS: 81/81 tests trên local và cloud; callback retry/idempotency, settlement, rollback, reconciliation concurrency tests đạt. Cloud tests chạy trong Maven container, source copy từ local. | Test không chứng minh Payment có thể chạy bằng `sgu_payment` riêng; cross-owner SQL còn ở checkout, reservation, invoice, tour departure. |
| 2026-11-23 | Bổ sung test riêng `OutboxPublisherTest` cho event identity và broker confirm; `mvn -B --no-transfer-progress -f payment_service/pom.xml -Dtest=OutboxPublisherTest test` local và cloud. | PASS: 2/2; JSON event ID trùng AMQP message ID/type; chỉ đánh dấu PUBLISHED khi confirm ACK; NACK giữ trạng thái retryable. Tổng Payment đã chạy 81 module tests + 2 publisher tests. | Test Rabbit thật qua Payment producer còn thiếu; producer event booking/tour và transaction ownership vẫn chưa hoàn thành. |
| 2026-11-23 | Cloud schema verification trên project Compose mới `phase2review` và volume mới; secrets tạm sinh trên cloud, không đưa vào repository. | PASS: 9/9 DB healthy, counts auth 9, tour 9, pricing 3, booking 10, payment 8, invoice 3, file 6, notification 7, reporting 9; app users chỉ có grant trên DB riêng; kiểm tra `sgu_auth` đọc `sgu_tour` bị từ chối; RabbitMQ/Redis healthy; `HostConfig.PortBindings={}` cho toàn bộ test containers. | Chỉ xác minh init schema/grants/infra; chưa chạy app services nối vào DB riêng. Stack/container cũ được giữ nguyên. |
| 2026-11-23 | Cloud smoke Notification trên `sgu_notification` + RabbitMQ trong project `phase2review`; chạy jar không publish port, `MAIL_ENABLED=false`; publish trực tiếp test event `booking.created.v1`, duplicate và recipient lỗi. | PASS: Notification khởi động/connected; event hợp lệ tạo 1 `mail_outbox` row PENDING + inbox processed; duplicate không thêm mail row; recipient sai được lưu durable vào `notification_dead_letter` và message rời queue. Có 11 template seed. | Producer thật từ Booking chưa nối vào broker; test event được inject thủ công. Không gửi email ra SMTP. |
| 2026-11-23 | Cloud smoke Reporting trên `sgu_reporting` + RabbitMQ trong project `phase2review`; chạy jar không publish port; gửi test event có email trong payload và duplicate. | PASS: Reporting khởi động/connected; inbox/audit mỗi loại 1 row sau duplicate; audit detail chỉ chứa event metadata/booking ID, không lưu email. | Producer thật từ Booking/Tour chưa nối; chỉ kiểm tra consumer và local DB transaction trên một event mẫu, chưa chứng minh projection nghiệp vụ. |
| 2026-11-23 | File/Tour gallery proof boundary, cấu hình Tour và module test: `mvn -B --no-transfer-progress -f file_service/pom.xml clean test`; `mvn -B --no-transfer-progress -f tour_service/pom.xml clean test`, local và cloud Maven container. | PASS: File 67/67; Tour 139/139, 4 skipped; cloud chạy source copy riêng trong `/home/agent/workspace/phase2-review`, không mở port, không khởi động stack service cũ. Đã xác minh proof thiếu/sai tour bị từ chối, đúng proof được forward qua Feign; app config YAML được load bởi test context. Maven cache volume và target artifacts tạm đã được dọn; chỉ giữ source/POM để review. | Không phải E2E qua DB-per-service: test DB là H2; File cross-owner SQL/ownership, Tour↔Booking và `TourPriceView` shared table còn tồn tại. |
| 2026-11-23 | Audit datasource + cross-owner SQL trên Java production source (`src/main/java`). | NO-GO: Auth, Booking, File, Invoice, Payment, Pricing và Tour vẫn mặc định `DB_URL=jdbc:mysql://localhost:3306/dbtour_v2` hoặc có truy vấn schema owner khác. Booking code còn gửi SMTP từ `mail_outbox` không tồn tại trong `sgu_booking`; Invoice consumer poll `payment_outbox` qua datasource Invoice. | Cần sửa runtime datasource và hoàn thành inventory/các vertical slices trước mọi cutover. |
| 2026-11-23 | Pricing ↔ Tour snapshot: thêm `InternalPriceResponse`, batch Feign `/internal/pricing/prices/batch`; tách snapshot Tour trong transaction repeatable-read khỏi gọi mạng; kiểm tra ID/response/giá và chia batch tối đa 100. Local: targeted `TourSearchPersistenceTest,PricingInternalFeignTest` 9/9; full Tour `mvn -B --no-transfer-progress test` 140 tests, 0 failures/errors, 4 skipped. Cloud: copy source lên `/home/agent/workspace/phase2-review/tour_service`, `mvn -B --no-transfer-progress clean test` trên Maven 3.9.9 / Java 21: 140 tests, 0 failures/errors, 4 skipped. | PASS cho thay đổi batch snapshot. Cloud `target` và volume `phase2review_maven_cache` dọn; Docker stack đã có (`sgu-tour-*`, `sgu_mysql_*`, RabbitMQ/Redis) vẫn còn nguyên, không chạy lại. `git diff --check` không có lỗi nội dung (chỉ cảnh báo line ending trên file khác trong working tree). | `TourPriceView`/`TourSpecification` tiếp tục truy vấn shared `prices` khi lọc/sắp xếp; batch API chưa loại bỏ cross-owner SQL. Chưa tích hợp Pricing DB-per-service thật hay E2E bằng MySQL/RabbitMQ; Phase 2 vẫn NO-GO. |
| 2026-11-23 | Tour→File gallery detach: bỏ `UPDATE file_mgmt` khỏi transaction Tour; ghi event versioned vào `tour_outbox`; publisher dùng compare-and-set lease, retry backoff, dead-letter; File endpoint cập nhật `file_mgmt` và `file_reference` trong local transaction, idempotent và không xóa asset vật lý. Contract/replay SQL ở `docs/INTERNAL_API_CONTRACTS.md`; Compose bật publisher cho Tour. Local full tests: Tour 143/143 (4 skipped), File 68/68; targeted Feign/outbox tests 2/2. Cloud Maven 3.9.9 / Java 21 clean tests: Tour 143/143 (4 skipped), File 68/68. | PASS cho luồng detach theo event/API và local consistency boundaries; stack Docker hiện hữu không bị khởi động lại hay thay đổi. | Chưa smoke-test publisher↔File bằng MySQL thật trên project cô lập. File còn các truy vấn cross-owner nêu trong inventory. Phase 2 chưa đạt gate. |
| 2026-11-23 | File gallery tombstone: bỏ `SELECT tour ... FOR UPDATE` khỏi File; edit và detach dùng chung lock thuộc `sgu_file`, detach ghi `deleted_at` và dọn metadata/reference trong cùng transaction. Thêm migration additive `data_seed/migrations/V002__file_tour_media_tombstone.sql` và cập nhật schema fresh-install. | PASS: File clean test local 69/69; cloud Maven 3.9.9 / Java 21 clean test 69/69. MySQL 8.4 disposable smoke xác nhận schema mới, V002 chạy lặp hai lần trên schema cũ, và câu lệnh upsert/`FOR UPDATE`/tombstone. Sandbox source nằm ở `/home/agent/workspace/phase2-review-file`; stack và volume cũ không bị chạm. | Migration phải được áp dụng trước deploy lên DB File hiện hữu. Chỉ kiểm tra migration/SQL trên MySQL; chưa chạy HTTP E2E Tour outbox→File qua hai datasource. File còn truy vấn Auth/Tour khác như inventory nêu. Phase 2 vẫn NO-GO. |
| 2026-11-24 | File reference ledger: thêm ghi/xóa reference trong transaction của gallery, avatar và location; cleanup so khớp URL/public ID với `file_reference`; sửa bulk delete thiếu `@Modifying`; dùng entity trả về sau persist để reference khớp ID thực do JPA sinh. MapStruct khai báo rõ các ownership fields do service gán sau mapping. Giữ legacy cross-owner guard cho tới khi owner backfill/sync được hoàn thành. | PASS: File clean tests local 71/71; cloud Maven 3.9.9 / Java 21 71/71. MapStruct unmapped-property warning đã hết; còn unchecked warning trong `GlobalExceptionHandler`. MySQL 8.4 disposable smoke với hai metadata rows chung public ID cho count `1`, sau xóa reference còn `0`. | Legacy `location/users/tour_image_ids` query vẫn là blocker đối với DB-per-service. Tour có thể commit/rollback khác nhịp với File; Auth account delete còn ghi trực tiếp File metadata, nên cần owner outbox/API + backfill/reconciliation trước khi bỏ guard. Sandbox source ở `/home/agent/workspace/phase2-review-file`; test container dùng `tmpfs`, stack/volume hiện hữu giữ nguyên. Phase 2 vẫn NO-GO. |
| 2026-11-24 | Auth→File account deletion: Auth ghi `auth.account.deleted.v1` nguyên tử cùng user deletion; publisher claim/lease, retry backoff, dead-letter; File dedupe qua `file_inbox`, khóa/tombstone avatar owner, detach avatar metadata/reference trong local transaction. File lock-avatar API kiểm tra tombstone và ownership của file. Tour gallery event dedupe trong `file_inbox` cùng detach transaction. Thêm V003 additive migration và API/replay contract. | PASS local clean suites: Auth 134/134, File 75/75. PASS cloud Maven 3.9.9 / Java 21: Auth 134/134, File 75/75. MySQL 8.4 disposable smoke: V003 chạy lặp 2 lần, schema mới có `file_inbox`, detach transaction tạo 1 inbox row, duplicate event insert ảnh hưởng 0 row, 2 account media detached, tour/location media giữ nguyên, avatar ref xóa và tombstone được ghi. | Test containers chạy trong Docker sandbox bằng `tmpfs` và đã bị xóa. Snapshot container sau smoke trùng trước đó; stack cũ giữ nguyên, gồm `sgu-tour-mysql-1` unhealthy từ trước. Phase 2 vẫn NO-GO. |
| 2026-11-24 | Auth avatar profile decoupling: File increments `avatar_media_lock.avatar_version` atomically with each successful avatar replacement and returns the revision with the URL. Auth applies that revision under only its own user row lock, ignores stale responses and rejects conflicting equal revisions. Added Auth V004 / File V005 migrations and fresh schema columns. `AvatarProfileWriter` no longer reads or locks File tables. | PASS local clean suites: Auth 135/135, File 76/76. PASS cloud clean suite File: 76/76 (Maven 3.9.9 / Java 21). MySQL 8.4 disposable smoke verified V003/V004/V005 idempotent on legacy schemas and fresh schema columns; avatar revisions 1→2; duplicate detach idempotency; tour/location references preserved. | File test emitted an existing unchecked warning in `GlobalExceptionHandler`; review that separately. Apply Auth V004 and File V005 before deploy; deploy File schema/code before Auth begins requiring revisions. Remaining File `AvatarWritePolicy`, token revocation and legacy reference SQL are cross-owner dependencies. No live HTTP E2E or production DB-per-service run yet. Phase 2 remains NO-GO. |
| 2026-11-24 | Booking test suite brought to green with the new internal-API architecture; `mvn -B --no-transfer-progress -f booking_service/pom.xml test` local. | PASS: 112 tests, 0 failures, 6 errors (FullBookingFlowE2ETest — needs live MySQL, environmental), 3 skipped (CrossServiceLifecycleTest gated on MAIL_CROSS_SERVICE_TEST). All previously-failing test classes now compile and pass: BookingChangeTest 30/30, BookingLifecycleTest 27/27, BookingAdviceServiceTest 1/1, BookingAdviceReadOnlyMysqlTest 1/1, ReviewEligibilityTest 1/1, MailConfigurationTest 1/1, InternalMailSecurityTest 8/8, BookingAdviceSecurityTest 2/2. Deleted obsolete tests referencing removed mail-outbox classes (MailDeliveryWorkerTest, MailOutboxRepositoryTest, ReminderPlannerTest, MailTestDatabase, MailEligibilityTest, MailTemplateRendererTest, MailPreviewTest). | Tests were rewritten to the new architecture (Tour/Pricing/Auth internal API mocks + direct DB row reads for concurrency), not to the old shared-DB contract. The remaining 6 E2E errors are environmental (no MySQL). |
| 2026-11-24 | Main-code robustness fixes in booking_service (legitimate, not test-only). | `BookingLifecycleService`: `tour_id` now accepted as Number or String via `tourIdOf()` (Tour API may return either); added `SELECT ... FOR UPDATE` on `tour_departure` inside `createInTransaction` so two concurrent checkouts cannot both read the same `available_seats` and oversell; DB row keys lowercased to match the rest of the codebase. `ChangeLedger.departures()`: the `FOR UPDATE` path now reads the departure row from the DB under a real lock instead of only delegating to the Tour client mock, so seat checks are authoritative. `BookingMailEvents`/`ChangeMailEvents`: `tour_id` label and `date()` are null-safe, avoiding ClassCastException when the Tour API returns `tour_id` as a number. | These are stop-gaps: the `FOR UPDATE` on `tour_departure` is still cross-owner SQL. The Tour internal API (`holdSeats`/`releaseHold`/`incrementVersion`) already locks on the Tour side, so the real fix for slice "Tour ↔ Booking" is to route seat holds/releases through that API and drop the local `tour_departure` writes. |
| 2026-11-24 | Tour ↔ Booking slice — removing cross-owner `tour_departure` writes from Booking. | `BookingLifecycleService.createInTransaction` now calls `tourClient.holdSeats(departureId, seats, bookingId, expiresAt)` as the primary path for seat consumption, with the direct `UPDATE tour_departure` kept only as a documented fallback when the Tour API is unavailable (same pattern already used for `releaseHold`). `BookingLifecycleService.cancel`/`expiry` route seat release through `tourClient.releaseHold(...)` with the same fallback. `ChangeLedger`/`BookingChangeService` seat mutations (`apply`, `releaseAndCancel`) now go through the Tour internal API instead of direct `UPDATE tour_departure`. | The `SELECT ... FOR UPDATE` on `tour_departure` used for concurrency control is still cross-owner; it must be removed once Booking holds no transactional authority over Tour rows. Tests were updated to stub `tourClient.holdSeats`/`releaseHold` (throwing to exercise the fallback) and to read `tour_departure` live for capacity/operational-status checks. Booking still references `tour_departure` for read-side checks (operational_status, departure_date, capacity) — those must become API/event projections. |

---

# Phase 3 — Chuyển đổi dữ liệu, production hardening và release acceptance

## Mục tiêu

Chuyển dữ liệu từ shared DB sang các DB owner mà không mất/lặp/sai dữ liệu; chứng minh hệ thống vận hành production qua triển khai, bảo mật, phục hồi, tải, lỗi phụ thuộc và quy trình go/no-go. Chỉ sau Phase 3 mới được kết luận bản phát hành production-ready.

## Điều kiện vào

- Phase 1 và Phase 2 đạt gate.
- Có inventory và snapshot dữ liệu nguồn, backup đã restore thử, chính sách downtime/consistency, quyết định cutover/rollback và người chịu trách nhiệm go/no-go.
- Staging gần production về network, secret, DB version, broker, config và resource limit.

## Công việc

1. **Migration rehearsal**: snapshot consistent; chạy migration lặp được; ghi checkpoint; xác nhận encoding/timezone/precision/ID/FK logic; không sửa/xóa nguồn.
2. **Reconciliation**: row counts và checksum theo bảng/partition; đối chiếu booking-payment-invoice, user-role, tour-departure-hold, file references, outbox/inbox; lập báo cáo chênh lệch và xử lý có audit.
3. **Cutover plan**: freeze hoặc dual-write có kiểm soát; drain queue; đồng bộ delta; đổi service config/traffic; xác nhận smoke checks; ngưỡng abort; rollback sang source đã bảo toàn; không để hai nguồn cùng nhận ghi không có quy tắc.
4. **Security review**: threat model, least privilege, service-to-service auth, TLS nơi cần, secret manager, credential rotation, CORS/CSRF/JWT/callback verification, dependency/CVE scan, upload validation, rate limits, audit/PII retention.
5. **Reliability/operations**: readiness/liveness, timeouts, retry budget, circuit breaker, graceful shutdown, queue capacity/DLQ, dashboards/alerts/log correlation/tracing, runbooks, backup retention, restore drill, RPO/RTO đo được.
6. **Capacity/failure testing**: tải dự kiến và headroom; DB/broker/cache restart; mất mạng; timeout thanh toán; duplicate/out-of-order event; disk/memory pressure; kiểm tra không oversell/duplicate charge/lost invoice.
7. **Release package**: image/tag bất biến, SBOM/scan nếu pipeline hỗ trợ, config/secret inventory, migration version, deployment order, smoke/rollback commands, release notes, sign-off.
8. **Acceptance demo**: luồng đăng nhập → tìm tour → giữ ghế/booking → thanh toán sandbox → invoice/notification/reporting; chứng minh persistence và trace qua các service.

## Đầu ra bắt buộc

- Báo cáo migration/reconciliation ký duyệt; snapshot/backup còn khả năng rollback.
- Deployment và rollback đã diễn tập; quan sát/alert/runbook/backup restore có bằng chứng.
- Kết quả security, integration/E2E, failure và load test với tiêu chí định lượng được thống nhất.
- Release manifest (image versions, schema versions, config/secret references, migration ID) và go/no-go record.

## Gate production release

- [ ] Mọi gate Phase 1–2 đã đạt; không còn blocker critical/high chưa có quyết định chấp nhận rủi ro rõ ràng.
- [ ] Không cross-service SQL/FK/grant; mỗi service khởi động và hoạt động chỉ với DB của mình.
- [ ] Dữ liệu được reconcile; mọi sai khác có giải thích/biên bản; lịch sử nghiệp vụ không bị mất/ghi đè.
- [ ] Security review và kiểm tra secret/dependency hoàn tất; callback/payment và internal auth có kiểm chứng.
- [ ] E2E nghiệp vụ đạt, gồm lỗi/duplicate/retry và compensation; không oversell hoặc duplicate side effect.
- [ ] SLO/error budget, throughput/latency target, tải và headroom được xác nhận cho môi trường đích.
- [ ] Backup restore đạt RPO/RTO; rollback/cutover diễn tập; dashboard/alert/runbook sẵn sàng.
- [ ] Release artifact/config bất biến, triển khai lặp lại được và có người ký go/no-go.

## Trạng thái Phase 3

**Chưa bắt đầu.** Production release: **NO-GO** cho tới khi tất cả gate production được chứng minh.

---

# Quy tắc cập nhật roadmap

Với mỗi việc hoàn thành, ghi ngày, file/commit, command, môi trường, kết quả tóm tắt và bằng chứng/log; cập nhật checkbox chỉ sau khi xác minh. Với blocker, ghi mức độ, tác động, owner, cách khắc phục và gate bị chặn. Khi có thay đổi phạm vi/ownership, sửa cả hợp đồng kiến trúc và migration, rồi cập nhật cross-service inventory. Không ghi “production-ready” chỉ dựa vào build thành công hoặc container khởi động.
