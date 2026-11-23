# Trang đặt sách (Spring Boot + Thymeleaf)

Ứng dụng web Java riêng để khách đặt sách online, chạy độc lập rồi ghép vào web bán sách chính.

## Chức năng

Giao diện khách hàng bằng tiếng Anh, cùng phong cách với web chính Linery Bookstore
(nền olive, font Playfair Display, thẻ trắng bo góc).

**Order by link** (`/order`), trang chính
- Khách dán **một hoặc nhiều link sách** (Tiki, Shopee, Fahasa, Amazon...), kèm tên sách (không bắt buộc) và số lượng
- Thông tin liên hệ: Full name, Email, Phone
- Payment method: VNPay, MoMo, Apple Pay, ZaloPay, Bank transfer, Cash on delivery
- Receiving method: Pickup at counter / Delivery (chọn Pickup thì ẩn ô địa chỉ)
- Ô voucher có nút Apply kiểm tra mã ngay
- Khung "Order summary" bên phải tự cập nhật theo các link khách nhập
- Gửi xong khách nhận mã đơn `LK...`. Cửa hàng kiểm tra link rồi nhập **báo giá** + lời nhắn
  trong trang quản trị; khách xem được khi tra cứu đơn

**Sách có sẵn + giỏ hàng + Checkout** (`/`, `/cart`, `/checkout`)
- Checkout cùng giao diện, có tính giảm giá voucher, phí ship (Pickup = miễn phí), trừ tồn kho
- Mã đơn `LB...`

**Track order** (`/track`): tra cứu cả hai loại đơn bằng mã đơn + số điện thoại

**Quản trị** (`/admin`, tiếng Việt, cần đăng nhập)
- Đơn theo link: lọc trạng thái, xem link, nhập báo giá, lời nhắn, đổi trạng thái
- Đơn giỏ hàng: đổi trạng thái (hủy đơn tự hoàn kho)
- Sách: thêm/sửa/ẩn, tồn kho

### Lưu ý về thanh toán online
VNPay / MoMo / ZaloPay / Apple Pay hiện mới **ghi nhận lựa chọn của khách**. Cửa hàng gửi yêu cầu
thanh toán (link/QR) cho khách sau khi xác nhận đơn. Muốn khách trả tiền tự động ngay trên trang thì
cần đăng ký tài khoản merchant với từng cổng (VNPay, MoMo, ZaloPay) để được cấp mã và khóa bí mật,
rồi tích hợp theo tài liệu của họ.

### Logo thanh toán
Chép các file logo đang dùng trên web chính vào `src/main/resources/static/img/payments/`
với tên `vnpay.png`, `momo.png`, `applepay.png`, `zalopay.png`, `bank.png`, `cod.png`.
Chưa có logo thì trang hiện chữ thay thế.

## Chạy bằng IntelliJ IDEA

1. Cần **JDK 17** trở lên.
2. IntelliJ → **File → Open** → chọn thư mục `bookorder` (chứa `pom.xml`) → *Open as Project*.
   Đợi Maven tải thư viện xong (thanh tiến trình góc dưới).
3. Mở `src/main/java/com/bookstore/order/BookOrderApplication.java` → bấm nút ▶ cạnh `main`.
4. Vào trình duyệt:
   - Order by link: http://localhost:8080/order
   - Sách có sẵn: http://localhost:8080
   - Quản trị: http://localhost:8080/admin — tài khoản `admin` / `admin123`

Lần chạy đầu tiên có sẵn 6 cuốn sách mẫu. Nếu đã chạy bản cũ, nên xóa thư mục `data/` để tạo lại CSDL. Dữ liệu lưu trong thư mục `data/` (CSDL H2), tắt mở lại vẫn còn.

## Cấu hình (`src/main/resources/application.properties`)

| Khóa | Ý nghĩa |
|---|---|
| `app.admin.username` / `app.admin.password` | Tài khoản quản trị. **Đổi trước khi đưa lên mạng.** |
| `app.shop.name` | Tên cửa hàng hiện trên trang |
| `app.shop.main-site-url` | Link nút "Về trang chính" |
| `app.shop.hotline` | Số hotline ở chân trang |
| `app.shop.shipping-fee` | Phí giao hàng (₫) |
| `app.shop.free-shipping-from` | Đơn từ mức này được miễn phí ship (₫) |
| `app.shop.bank-info` | Thông tin chuyển khoản hiện cho khách |
| `app.shop.vouchers` | Mã giảm giá, dạng `MA:phần-trăm`, vd `WELCOME10:10,LINERY15:15` |
| `server.servlet.context-path` | Đặt `/order` nếu muốn chạy ở `tenmien.vn/order` |

Màu sắc: sửa các biến ở đầu `static/css/style.css` (`--olive`, `--gold`, ...).

### Dùng MySQL thay H2
1. Trong `pom.xml`: bỏ comment khối `mysql-connector-j`.
2. Trong `application.properties`: comment khối H2, bỏ comment khối MySQL, điền user/mật khẩu.
3. Tạo CSDL trống: `CREATE DATABASE bookorder CHARACTER SET utf8mb4;` — bảng sẽ tự được tạo.

## Ghép vào web chính

Chọn một trong các cách (dễ → khó):

1. **Đặt link/nút** trên web chính (ví dụ mục "Order" trên menu) trỏ tới `/order` của ứng dụng này,
   chạy ở tên miền phụ như `order.tenmien.vn`. Không phải sửa gì web chính ngoài thêm một liên kết.
2. **Cùng tên miền qua reverse proxy** (Nginx), để trang đặt sách nằm ở `tenmien.vn/order`:
   - đặt `server.servlet.context-path=/order`
   - Nginx:
     ```nginx
     location /order/ {
         proxy_pass http://127.0.0.1:8080/order/;
         proxy_set_header Host $host;
         proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
         proxy_set_header X-Forwarded-Proto $scheme;
     }
     ```
3. **Nhúng iframe** vào một trang của web chính:
   ```html
   <iframe src="https://tenmien.vn/order/" style="width:100%;height:1200px;border:0"></iframe>
   ```
   Nếu web chính **khác tên miền**, trong `SecurityConfig.java` thay `f.sameOrigin()` bằng
   cấu hình CSP `frame-ancestors https://tenmien-web-chinh.vn` (hoặc nhờ người làm web hỗ trợ).
4. **Gộp code** vào web chính nếu web chính cũng là Spring Boot: chép các package
   `model`, `repository`, `service`, `controller`, `dto` và thư mục `templates`, `static/css`.

### Lấy sách từ web chính
Nếu web chính có CSDL sách riêng, có thể nhập sách vào trang quản trị (`/admin/sach`),
hoặc trỏ `spring.datasource.*` sang CSDL đó và sửa `model/Book.java` cho khớp tên bảng/cột
(`@Table(name = "...")`, `@Column(name = "...")`).

## Đưa lên server

```bash
mvn clean package
java -jar target/bookorder-1.0.0.jar
```

## Cấu trúc

```
src/main/java/com/bookstore/order/
├── BookOrderApplication.java   # chạy ứng dụng
├── config/      # bảo mật trang admin, dữ liệu mẫu
├── controller/  # ShopController (khách), CartController (giỏ + đặt hàng), AdminController
├── dto/         # form Checkout, form Order by link (dùng chung OrderContactForm)
├── model/       # Book, CustomerOrder, LinkOrder, LinkItem, PaymentMethod, ReceivingMethod
├── repository/  # truy vấn CSDL
└── service/     # CartService (giỏ theo phiên), OrderService, ShopSettings
src/main/resources/
├── application.properties
├── templates/   # giao diện Thymeleaf (khách + admin/)
└── static/       # css/style.css, js/order.js, img/payments/
```
