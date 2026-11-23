package com.bookstore.order.model;

/** Loại bìa khách chọn cho mỗi cuốn order theo link */
public enum CoverType {
    PAPERBACK("Paperback", "Bìa mềm"),
    HARDCOVER("Hardcover", "Bìa cứng"),
    LARGE_PRINT("Large Print", "Bản khổ lớn");

    private final String label;
    private final String viLabel;

    CoverType(String label, String viLabel) {
        this.label = label;
        this.viLabel = viLabel;
    }

    public String getLabel() { return label; }
    public String getViLabel() { return viLabel; }
}
