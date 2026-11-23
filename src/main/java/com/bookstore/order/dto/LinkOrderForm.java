package com.bookstore.order.dto;

import com.bookstore.order.model.LinkPaymentOption;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;

/** Form "Order by link": khách dán từng link, bấm OK để thêm vào danh sách (items) */
public class LinkOrderForm extends OrderContactForm {

    @Valid
    @NotEmpty(message = "Please add at least one book link")
    @Size(max = 20, message = "Maximum 20 links per order")
    private List<LinkItemForm> items = new ArrayList<>();

    @NotNull(message = "Please choose a payment option")
    private LinkPaymentOption paymentOption = LinkPaymentOption.BANK_FULL;

    public List<LinkItemForm> getItems() { return items; }
    public void setItems(List<LinkItemForm> items) { this.items = items; }
    public LinkPaymentOption getPaymentOption() { return paymentOption; }
    public void setPaymentOption(LinkPaymentOption paymentOption) { this.paymentOption = paymentOption; }
}
