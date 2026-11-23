package com.bookstore.order.dto;

import com.bookstore.order.model.PaymentMethod;
import jakarta.validation.constraints.NotNull;

/** Form thanh toán giỏ hàng (sách có sẵn trong cửa hàng) */
public class CheckoutForm extends OrderContactForm {

    @NotNull(message = "Please choose a payment method")
    private PaymentMethod paymentMethod = PaymentMethod.VNPAY;

    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(PaymentMethod paymentMethod) { this.paymentMethod = paymentMethod; }
}
