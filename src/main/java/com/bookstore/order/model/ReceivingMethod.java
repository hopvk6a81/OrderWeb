package com.bookstore.order.model;

public enum ReceivingMethod {
    PICKUP("Pickup at counter", "Nhận tại quầy"),
    DELIVERY("Delivery", "Giao hàng");

    private final String label;
    private final String viLabel;

    ReceivingMethod(String label, String viLabel) {
        this.label = label;
        this.viLabel = viLabel;
    }

    public String getLabel() { return label; }
    public String getViLabel() { return viLabel; }
}
