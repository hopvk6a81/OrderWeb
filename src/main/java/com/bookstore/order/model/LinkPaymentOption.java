package com.bookstore.order.model;

/** Hai cách thanh toán cho đơn order theo link */
public enum LinkPaymentOption {
    /** Chuyển khoản toàn bộ tiền sách -> miễn phí giao hàng */
    BANK_FULL("Bank transfer – full payment", "Chuyển khoản toàn bộ (free ship)"),
    /** Trả khi nhận hàng, nhưng phải chuyển khoản cọc trước một phần giá bìa */
    COD_DEPOSIT("Cash on delivery – deposit", "COD – cọc trước");

    private final String label;
    private final String viLabel;

    LinkPaymentOption(String label, String viLabel) {
        this.label = label;
        this.viLabel = viLabel;
    }

    public String getLabel() { return label; }
    public String getViLabel() { return viLabel; }
}
