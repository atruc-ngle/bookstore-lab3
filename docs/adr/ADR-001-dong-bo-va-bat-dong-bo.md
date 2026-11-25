# ADR-001: order-service gọi catalog-service đồng bộ qua REST, bọc timeout và cầu dao (circuit breaker)

- **Trạng thái:** Đã chấp nhận
- **Ngày:** 2026-11-25
- **Người quyết định:** Nguyễn Lê Trúc Anh - B2305559
- **Liên quan:** Lab 3 (TH3); `CatalogClientConfig`, `CatalogClient`, `CatalogCircuitBreakerTest`, `CatalogClientContractTest`

## 1. Bối cảnh
`order-service` (8082) phải biết giá và sự tồn tại của sách trước khi tạo đơn; thông tin này thuộc `catalog-service` (8081).
Nếu catalog chậm hoặc tắt mà order vẫn chờ, các luồng xử lý bị giữ lại, hàng đợi đầy lên và lỗi lan sang toàn bộ
luồng đặt hàng. Yêu cầu: người dùng nhận được phản hồi (thành công hoặc lỗi rõ ràng) trong vài giây; sự cố ở catalog
không được lan sang order; giá đơn hàng phải là giá hiện hành tại lúc đặt.

## 2. Các phương án đã xem xét
| Phương án | Ưu điểm | Nhược điểm |
|---|---|---|
| A. Gọi đồng bộ REST, không timeout/cầu dao | Đơn giản nhất; dữ liệu luôn mới | Catalog chậm/tắt thì order treo theo |
| B. Gọi đồng bộ REST + timeout + cầu dao + 503 kèm Retry-After | Lỗi nhanh, có kiểm soát; giá luôn mới; ít hạ tầng; dễ kiểm thử bằng WireMock | Order vẫn phụ thuộc catalog lúc đặt; phải chọn và tinh chỉnh ngưỡng |
| C. Bất đồng bộ qua message broker (Kafka/RabbitMQ), đơn ở trạng thái PENDING | Tách thời gian sống hai dịch vụ; chịu lỗi tốt | Thêm broker cần vận hành; nhất quán cuối cùng (giá có thể đổi, phải huỷ đơn); khó kiểm thử; quá nặng cho quy mô hiện tại |
| D. Sao chép danh mục sang order-service, cập nhật theo sự kiện | Không cần gọi mạng khi đặt | Dữ liệu có thể cũ; thêm cơ chế đồng bộ; trùng lặp dữ liệu |

## 3. Quyết định
Chúng tôi chọn **phương án B**: gọi `catalog-service` đồng bộ bằng `RestClient` với
- timeout kết nối **1 s**, timeout đọc **2 s**;
- cầu dao `catalog` (Resilience4j): cửa sổ trượt **10** lời gọi, tối thiểu **5** lời gọi mới tính, ngưỡng lỗi **50 %**,
  ngưỡng gọi chậm **1500 ms**, giữ trạng thái MỞ **15 s**, nửa mở cho phép **2** lời gọi thử;
- lỗi nghiệp vụ 404 (`UnknownBookException`) không tính là lỗi cầu dao và trả về 400;
- mọi lỗi hạ tầng (timeout, từ chối kết nối, cầu dao mở) → **503** `upstream-unavailable` kèm `Retry-After: 10`;
- `POST /api/v1/orders` có `Idempotency-Key` để client thử lại an toàn khi nhận 503.

Chưa chọn bất đồng bộ (C) vì quy mô hiện tại chưa đủ để bù chi phí vận hành broker và độ phức tạp nhất quán.

## 4. Hệ quả
- Tích cực: sự cố catalog chỉ khiến yêu cầu đặt hàng bị từ chối **nhanh** (< 1,4 s trong kiểm thử), không treo luồng;
  giá luôn hiện hành; thử lại an toàn nhờ khoá chống trùng.
- Tiêu cực / rủi ro chấp nhận: khi catalog tắt thì không đặt được hàng; ngưỡng cầu dao có thể chưa tối ưu.
- Việc phải làm tiếp theo: theo dõi số lần chuyển trạng thái cầu dao qua log/metrics; cân nhắc phương án C hoặc D nếu
  lưu lượng tăng; áp dụng cùng khuôn mẫu cho `inventory-service`.

## 5. Cách kiểm chứng
- `CatalogCircuitBreakerTest`: catalog chậm 1500 ms, timeout đọc 300 ms → 5 lần POST đều 503 trong < 1400 ms,
  WireMock chỉ nhận đúng 2 yêu cầu.
- `CatalogClientContractTest`: hợp đồng `getBookFound` / `getBookNotFound` bảo đảm hình dạng giao tiếp.
- `OrderApiIntegrationTest`: cùng `Idempotency-Key` không tạo đơn thứ hai.
- Xem xét lại khi tỉ lệ 503 do cầu dao vượt ngưỡng SLO, hoặc khi thêm dịch vụ thứ ba nằm trong chuỗi gọi đồng bộ.
