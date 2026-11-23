package com.bookstore.order.service;

import com.bookstore.order.dto.CheckoutForm;
import com.bookstore.order.dto.LinkOrderForm;
import com.bookstore.order.dto.OrderContactForm;
import com.bookstore.order.model.*;
import com.bookstore.order.repository.BookRepository;
import com.bookstore.order.repository.LinkOrderRepository;
import com.bookstore.order.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Predicate;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final LinkOrderRepository linkOrderRepository;
    private final BookRepository bookRepository;
    private final ShopSettings shop;
    private final LinkPricing linkPricing;
    private final CurrencyRates rates;
    private final SecureRandom random = new SecureRandom();

    public OrderService(OrderRepository orderRepository, LinkOrderRepository linkOrderRepository,
                        BookRepository bookRepository, ShopSettings shop, LinkPricing linkPricing,
                        CurrencyRates rates) {
        this.orderRepository = orderRepository;
        this.linkOrderRepository = linkOrderRepository;
        this.bookRepository = bookRepository;
        this.shop = shop;
        this.linkPricing = linkPricing;
        this.rates = rates;
    }

    /** Phí giao hàng: nhận tại quầy = 0, giao hàng = theo cấu hình */
    public long shippingFor(ReceivingMethod method, long amountAfterDiscount) {
        return method == ReceivingMethod.PICKUP ? 0 : shop.shippingFeeFor(amountAfterDiscount);
    }

    /**
     * Tạo đơn từ giỏ hàng. Kiểm tra tồn kho và trừ kho trong cùng transaction.
     * @throws IllegalStateException nếu giỏ trống hoặc sách không đủ hàng
     */
    @Transactional
    public CustomerOrder placeOrder(CheckoutForm form, List<CartService.CartLine> lines) {
        if (lines.isEmpty()) throw new IllegalStateException("Your cart is empty");

        CustomerOrder order = new CustomerOrder();
        order.setCode(generateCode("LB", orderRepository::existsByCode));
        order.setCustomerName(form.getCustomerName().trim());
        order.setPhone(form.getPhone().trim());
        order.setEmail(blankToNull(form.getEmail()));
        order.setPaymentMethod(form.getPaymentMethod());
        order.setReceivingMethod(form.getReceivingMethod());
        copyAddress(form, order::setAddress, order::setCity, order::setPostalCode);
        order.setNote(blankToNull(form.getNotes()));

        long subtotal = 0;
        for (CartService.CartLine line : lines) {
            Book book = bookRepository.findById(line.getBook().getId())
                    .orElseThrow(() -> new IllegalStateException("A book in your cart is no longer available"));
            if (book.getStock() < line.getQuantity()) {
                throw new IllegalStateException("\"" + book.getTitle() + "\" has only "
                        + book.getStock() + " left in stock");
            }
            book.setStock(book.getStock() - line.getQuantity());
            order.addItem(new OrderItem(book.getId(), book.getTitle(), book.getPrice(), line.getQuantity()));
            subtotal += book.getPrice() * line.getQuantity();
        }

        long discount = shop.discountFor(subtotal, form.getVoucherCode());
        if (discount > 0) order.setVoucherCode(form.getVoucherCode().trim().toUpperCase());
        long shipping = shippingFor(form.getReceivingMethod(), subtotal - discount);

        order.setSubtotal(subtotal);
        order.setDiscount(discount);
        order.setShippingFee(shipping);
        order.setTotal(subtotal - discount + shipping);
        return orderRepository.save(order);
    }

    /** Một cuốn sách trong đơn theo link, đã xác định giá bìa */
    /** coverPriceVnd = null nghĩa là chưa đọc được giá -> chờ cửa hàng báo giá */
    public record ResolvedItem(String url, String title, String imageUrl, java.math.BigDecimal originalPrice,
                               String currency, Long coverPriceVnd, boolean autoPrice,
                               com.bookstore.order.model.CoverType coverType, int quantity, String isbn) {}

    @Transactional
    public LinkOrder placeLinkOrder(LinkOrderForm form, List<ResolvedItem> items) {
        LinkOrder o = new LinkOrder();
        o.setCode(generateCode("LK", linkOrderRepository::existsByCode));
        o.setCustomerName(form.getCustomerName().trim());
        o.setEmail(blankToNull(form.getEmail()));
        o.setPhone(form.getPhone().trim());
        o.setPaymentOption(form.getPaymentOption());
        o.setReceivingMethod(form.getReceivingMethod());
        copyAddress(form, o::setAddress, o::setCity, o::setPostalCode);
        o.setNotes(blankToNull(form.getNotes()));
        int pct = shop.voucherPercent(form.getVoucherCode()).orElse(0);
        if (pct > 0) {
            o.setVoucherCode(form.getVoucherCode().trim().toUpperCase());
            o.setVoucherPercent(pct);
        }
        for (ResolvedItem i : items) {
            Long vnd = i.coverPriceVnd();
            LinkItem item = new LinkItem(i.url(), blankToNull(i.title()), blankToNull(i.imageUrl()),
                    i.originalPrice(), i.currency(), vnd, vnd == null ? null : linkPricing.unitPrice(vnd),
                    i.autoPrice(), i.coverType(), i.quantity());
            item.setIsbn(i.isbn());
            o.getItems().add(item);
        }
        o.setStatus(o.hasPendingPrice() ? LinkOrder.Status.AWAITING_QUOTE : LinkOrder.Status.AWAITING_PAYMENT);
        applyTotals(o);
        return linkOrderRepository.save(o);
    }

    /** Tính lại toàn bộ tiền của đơn theo link từ giá bìa từng cuốn */
    public void applyTotals(LinkOrder o) {
        List<LinkPricing.Line> lines = o.getItems().stream()
                .filter(i -> i.getCoverPrice() != null)
                .map(i -> new LinkPricing.Line(i.getCoverPrice(), i.getQuantity()))
                .toList();
        LinkPricing.Totals t = linkPricing.compute(lines, o.getPaymentOption(), o.getReceivingMethod(), o.getVoucherPercent());
        o.setCoverTotal(t.coverTotal());
        o.setSubtotal(t.subtotal());
        o.setDiscount(t.discount());
        o.setShippingFee(t.shippingFee());
        o.setTotal(t.total());
        o.setDeposit(t.deposit());
        o.setPayNow(t.payNow());
        o.setPayOnDelivery(t.payOnDelivery());
    }

    /** Cửa hàng sửa giá bìa gốc + loại tiền (khi đọc sai), trạng thái, lời nhắn */
    @Transactional
    public void updateLinkOrder(Long id, LinkOrder.Status status, String shopNote,
                                List<java.math.BigDecimal> amounts, List<String> currencies,
                                List<com.bookstore.order.model.CoverType> coverTypes) {
        LinkOrder o = linkOrderRepository.findById(id).orElseThrow();
        o.setStatus(status);
        o.setShopNote(blankToNull(shopNote));
        if (coverTypes != null) {
            for (int i = 0; i < o.getItems().size() && i < coverTypes.size(); i++) {
                if (coverTypes.get(i) != null) o.getItems().get(i).setCoverType(coverTypes.get(i));
            }
        }
        if (amounts != null && currencies != null) {
            for (int i = 0; i < o.getItems().size() && i < amounts.size() && i < currencies.size(); i++) {
                java.math.BigDecimal amount = amounts.get(i);
                String cur = currencies.get(i);
                Long vnd = rates.toVnd(amount, cur);
                if (vnd == null || vnd <= 0) continue;
                LinkItem item = o.getItems().get(i);
                boolean changed = item.getOriginalPrice() == null || amount.compareTo(item.getOriginalPrice()) != 0
                        || !cur.equalsIgnoreCase(item.getCurrency());
                item.setOriginalPrice(amount);
                item.setCurrency(cur.toUpperCase());
                item.setCoverPrice(vnd);
                item.setUnitPrice(linkPricing.unitPrice(vnd));
                if (changed) item.setAutoPrice(false); // giá do cửa hàng nhập
            }
        }
        // Đã báo giá đủ tất cả các cuốn -> chuyển sang chờ khách thanh toán
        if (!o.hasPendingPrice() && o.getStatus() == LinkOrder.Status.AWAITING_QUOTE) {
            o.setStatus(LinkOrder.Status.AWAITING_PAYMENT);
        }
        applyTotals(o);
    }

    @Transactional
    public void updateStatus(Long orderId, OrderStatus newStatus) {
        CustomerOrder order = orderRepository.findById(orderId).orElseThrow();
        OrderStatus old = order.getStatus();
        if (old == newStatus) return;

        // Hủy đơn -> hoàn lại kho; mở lại đơn đã hủy -> trừ kho lại
        if (newStatus == OrderStatus.CANCELLED) adjustStock(order, +1);
        else if (old == OrderStatus.CANCELLED) adjustStock(order, -1);
        order.setStatus(newStatus);
    }

    private void adjustStock(CustomerOrder order, int sign) {
        for (OrderItem item : order.getItems()) {
            bookRepository.findById(item.getBookId()).ifPresent(b ->
                    b.setStock(Math.max(0, b.getStock() + sign * item.getQuantity())));
        }
    }

    private void copyAddress(OrderContactForm form, java.util.function.Consumer<String> address,
                             java.util.function.Consumer<String> city,
                             java.util.function.Consumer<String> postal) {
        if (form.isDelivery()) {
            address.accept(blankToNull(form.getAddress()));
            city.accept(blankToNull(form.getCity()));
            postal.accept(blankToNull(form.getPostalCode()));
        }
    }

    private String generateCode(String prefix, Predicate<String> exists) {
        String datePart = LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd"));
        String code;
        do {
            code = prefix + datePart + String.format("%04d", random.nextInt(10000));
        } while (exists.test(code));
        return code;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
