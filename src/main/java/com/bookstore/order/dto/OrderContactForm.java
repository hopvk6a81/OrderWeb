package com.bookstore.order.dto;

import com.bookstore.order.model.ReceivingMethod;
import jakarta.validation.constraints.*;

/**
 * Các ô dùng chung cho trang Checkout (giỏ hàng) và trang Order by link:
 * thông tin liên hệ, cách nhận hàng, địa chỉ, voucher.
 */
public abstract class OrderContactForm {

    @NotBlank(message = "Please enter your full name")
    @Size(max = 100)
    private String customerName;

    @NotBlank(message = "Please enter your email")
    @Email(message = "Invalid email address")
    private String email;

    @NotBlank(message = "Please enter your phone number")
    @Pattern(regexp = "^(0|\\+84)[0-9]{9,10}$", message = "Invalid phone number")
    private String phone;

    @NotNull(message = "Please choose a receiving method")
    private ReceivingMethod receivingMethod = ReceivingMethod.DELIVERY;

    /** Bắt buộc khi chọn Delivery – kiểm tra trong FormChecks */
    @Size(max = 500)
    private String address;

    @Size(max = 100)
    private String city;

    @Size(max = 20)
    private String postalCode;

    @Size(max = 1000)
    private String notes;

    @Size(max = 50)
    private String voucherCode;

    public boolean isDelivery() { return receivingMethod == ReceivingMethod.DELIVERY; }

    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
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
}
