# User Banners Archive Job

Spring Batch job để di chuyển toàn bộ dữ liệu từ `user_banners` sang
`user_banners_archive` mỗi ngày, tối ưu cho bảng ~10 triệu bản ghi trên PostgreSQL.

## Vì sao thiết kế như thế này

| Vấn đề | Giải pháp |
|---|---|
| 10 triệu dòng kéo qua JVM rất chậm | Toàn bộ move thực hiện bằng 1 câu SQL `WITH DELETE...RETURNING...INSERT` chạy thẳng trong Postgres |
| 1 transaction 10 triệu dòng sẽ khóa bảng lâu, phình WAL, dễ timeout | Chia thành nhiều batch nhỏ (mặc định 20.000 dòng), mỗi batch = 1 transaction riêng |
| Dữ liệu mới liên tục được insert trong lúc job chạy | Snapshot `MAX(id)` lúc bắt đầu, chỉ archive `id <= maxId` |
| Job có thể fail giữa chừng | Mỗi batch tự commit → batch đã chạy xong không mất; chạy lại job sẽ tự tiếp tục vì các dòng đã archive không còn trong bảng gốc nữa |
| Trùng lặp khi retry | `ON CONFLICT (id) DO NOTHING` ở bảng archive |

## Trước khi chạy

1. Chạy `src/main/resources/db/archive-schema.sql` để tạo bảng `user_banners_archive`.
2. Sửa `application.yml`: thông tin kết nối DB, cron, batch-size.
3. Lần đầu để `spring.batch.jdbc.initialize-schema: always` để Spring Batch tự tạo bảng
   metadata (`BATCH_JOB_INSTANCE`, `BATCH_STEP_EXECUTION`,...). Sau đó đổi sang `never`
   và quản lý bảng này qua Flyway/Liquibase như các bảng khác.

## Build & chạy

```bash
mvn clean package
java -jar target/user-banners-archive-job-1.0.0.jar
```

Job sẽ tự chạy theo cron cấu hình (mặc định 2h sáng). Muốn chạy thủ công ngay lập tức,
có thể expose thêm 1 REST endpoint hoặc gọi `jobLauncher.run(...)` từ `CommandLineRunner`.

## Tuning cho 10 triệu bản ghi

- **batch-size**: 20.000 là điểm khởi đầu an toàn. Có thể tăng lên 50.000 nếu server
  DB khỏe và ít traffic vào giờ chạy job; giảm xuống 5.000–10.000 nếu cần job "nhẹ tay"
  hơn với các query khác đang chạy song song.
- **sleep-between-batches-ms**: nghỉ giữa các batch để tránh spike CPU/IO trên DB,
  đặc biệt nếu bảng vẫn được đọc/ghi bởi ứng dụng chính trong lúc archive chạy.
- Nên chạy job vào khung giờ thấp điểm (mặc định 2h sáng).
- Sau khi archive xong 1 lượng lớn dữ liệu, nên `VACUUM (ANALYZE) user_banners;`
  định kỳ (autovacuum của Postgres thường tự lo việc này, nhưng có thể chạy thủ công
  nếu cần giải phóng không gian ngay).
- Đảm bảo cột `id` là PK (đã có sẵn `BIGSERIAL PRIMARY KEY`) — đây là index bắt buộc
  để câu lệnh batch chạy nhanh.

## Theo dõi tiến độ / giám sát

Spring Batch tự ghi lại tiến trình vào các bảng `BATCH_JOB_EXECUTION`,
`BATCH_STEP_EXECUTION` — có thể query các bảng này để biết:
- Job đang chạy hay đã xong
- Số dòng đã ghi (`write_count`) ở step `archiveBatchStep`
- Thời gian bắt đầu/kết thúc, lỗi (nếu có)

## Nâng cao (tuỳ nhu cầu mở rộng sau này)

- **Chạy song song theo dải id** (Spring Batch Partitioning) nếu 1 luồng không đủ
  nhanh — có thể tách range id thành N partition, mỗi partition 1 thread archive
  song song. Đánh đổi: phức tạp hơn, cần theo dõi lock contention.
- **Partition bảng archive theo tháng** (`PARTITION BY RANGE (created_at)`) để sau
  này query/xoá dữ liệu cũ trên archive nhanh hơn — đã có gợi ý trong
  `archive-schema.sql`.
- Nếu yêu cầu thực sự là "làm rỗng hẳn bảng nguồn mỗi ngày" (không phải archive dần),
  và có thể chấp nhận gián đoạn ghi trong vài giây, kỹ thuật
  `ALTER TABLE ... RENAME` + tạo bảng mới trống sẽ nhanh hơn nữa (gần như tức thời),
  sau đó archive bảng cũ (đã đổi tên) ở background. Nói cho mình biết nếu bạn cần
  hướng này — cách này khác về mặt vận hành (cần điều phối với tầng ghi dữ liệu).
