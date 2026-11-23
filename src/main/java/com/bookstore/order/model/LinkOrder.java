package com.bookstore.order.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Đơn "order sách theo link": khách dán link sách ở trang khác,
 * hệ thống đọc giá bìa và tính giá bán = giá bìa × 70%.
 */
@Entity
@Table(name = "link_orders")
public class LinkOrder {

    public enum Status {
        AWAITING_QUOTE("Waiting for price confirmation", "Chờ báo giá"),
        AWAITING_PAYMENT("Awaiting payment", "Chờ thanh toán / cọc"),
        PAID("Payment received", "Đã nhận tiền / cọc"),
        ORDERED("Ordered from supplier", "Đã đặt nhà cung cấp"),
        ARRIVED("Books arrived at our store", "Sách đã về"),
        SHIPPING("On the way", "Đang giao"),
        COMPLETED("Completed", "Hoàn thành"),
        CANCELLED("Cancelled", "Đã hủy");

        private final String label;
        private final String viLabel;

        Status(String label, String viLabel) {
            this.label = label;
            this.viLabel = viLabel;
        }

        public String getLabel() { return label; }
        public String getViLabel() { return viLabel; }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String code;

    private String customerName;
    private String email;
    private String phone;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "link_order_items", joinColumns = @JoinColumn(name = "link_order_id"))
    @OrderColumn(name = "position")
    private List<LinkItem> items = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    private LinkPaymentOption paymentOption;

    @Enumerated(EnumType.STRING)
    private ReceivingMethod receivingMethod;

    @Column(length = 1000)
    private String address;
    private String city;
    private String postalCode;

    @Column(length = 2000)
    private String notes;

    private String voucherCode;
    private int voucherPercent;

    // ---- Tiền (VND) ----
    private long coverTotal;     // tổng giá bìa
    private long subtotal;       // tổng giá bán (70%)
    private long discount;       // giảm giá voucher
    private long shippingFee;
    private long total;          // tổng phải trả
    private long deposit;        // tiền cọc (COD)
    private long payNow;         // chuyển khoản ngay
    private long payOnDelivery;  // trả khi nhận hàng

    @Enumerated(EnumType.STRING)
    private Status status = Status.AWAITING_PAYMENT;

    /** Lời nhắn của cửa hàng, khách xem được khi tra cứu đơn */
    @Column(length = 2000)
    private String shopNote;

    private LocalDateTime createdAt = LocalDateTime.now();

    public int getTotalQuantity() {
        return items.stream().mapToInt(LinkItem::getQuantity).sum();
    }

    /** Còn cuốn chưa có giá (không đọc được từ web) -> chờ cửa hàng báo giá */
    public boolean hasPendingPrice() {
        return items.stream().anyMatch(i -> i.getCoverPrice() == null);
    }

    public long getPendingCount() {
        return items.stream().filter(i -> i.getCoverPrice() == null).count();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public List<LinkItem> getItems() { return items; }
    public void setItems(List<LinkItem> items) { this.items = items; }
    public LinkPaymentOption getPaymentOption() { return paymentOption; }
    public void setPaymentOption(LinkPaymentOption paymentOption) { this.paymentOption = paymentOption; }
    public ReceivingMethod getReceivingMethod() { return receivingMethod; }
    public void setReceivingMethod(ReceivingMethod receivingMethod) { this.receivingMethod = receivingMethod; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getPostalCode() { return postalCode; }
    public void setPostalCode(String postalCode) { this.postalCode = postalCode; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getVoucherCode() { return voucherCode; }
    public void setVoucherCode(String voucherCode) { this.voucherCode = voucherCode; }
    public int getVoucherPercent() { return voucherPercent; }
    public void setVoucherPercent(int voucherPercent) { this.voucherPercent = voucherPercent; }
    public long getCoverTotal() { return coverTotal; }
    public void setCoverTotal(long coverTotal) { this.coverTotal = coverTotal; }
    public long getSubtotal() { return subtotal; }
    public void setSubtotal(long subtotal) { this.subtotal = subtotal; }
    public long getDiscount() { return discount; }
    public void setDiscount(long discount) { this.discount = discount; }
    public long getShippingFee() { return shippingFee; }
    public void setShippingFee(long shippingFee) { this.shippingFee = shippingFee; }
    public long getTotal() { return total; }
    public void setTotal(long total) { this.total = total; }
    public long getDeposit() { return deposit; }
    public void setDeposit(long deposit) { this.deposit = deposit; }
    public long getPayNow() { return payNow; }
    public void setPayNow(long payNow) { this.payNow = payNow; }
    public long getPayOnDelivery() { return payOnDelivery; }
    public void setPayOnDelivery(long payOnDelivery) { this.payOnDelivery = payOnDelivery; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getShopNote() { return shopNote; }
    public void setShopNote(String shopNote) { this.shopNote = shopNote; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
