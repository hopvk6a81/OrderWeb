package com.bookstore.order.model;

public enum OrderStatus {
    NEW("Order placed", "Mới đặt"),
    CONFIRMED("Confirmed", "Đã xác nhận"),
    SHIPPING("On the way", "Đang giao"),
    COMPLETED("Completed", "Hoàn thành"),
    CANCELLED("Cancelled", "Đã hủy");

    /** Nhãn tiếng Anh cho khách */
    private final String label;
    /** Nhãn tiếng Việt cho trang quản trị */
    private final String viLabel;

    OrderStatus(String label, String viLabel) {
        this.label = label;
        this.viLabel = viLabel;
    }

    public String getLabel() { return label; }
    public String getViLabel() { return viLabel; }
}
