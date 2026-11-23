package com.bookstore.order.model;

public enum PaymentMethod {
    VNPAY("VNPay - Wallet", "Pay with your VNPay wallet.", "vnpay", "VNPAY", true),
    MOMO("MoMo wallet", "Pay securely with your MoMo wallet.", "momo", "MoMo", true),
    APPLE_PAY("Apple Pay", "Pay quickly with Apple Pay.", "applepay", "Pay", true),
    ZALOPAY("ZaloPay", "Pay securely with your ZaloPay wallet.", "zalopay", "Zalo", true),
    BANK_TRANSFER("Bank transfer", "Complete the transfer using our payment details.", "bank", "BANK", false),
    COD("Cash on delivery", "Pay in cash when you receive your books.", "cod", "COD", false);

    private final String label;
    private final String description;
    /** Tên file logo trong static/img/payments/{icon}.png */
    private final String icon;
    /** Chữ hiển thị thay logo nếu chưa có file ảnh */
    private final String shortName;
    private final boolean wallet;

    PaymentMethod(String label, String description, String icon, String shortName, boolean wallet) {
        this.label = label;
        this.description = description;
        this.icon = icon;
        this.shortName = shortName;
        this.wallet = wallet;
    }

    public String getLabel() { return label; }
    public String getDescription() { return description; }
    public String getIcon() { return icon; }
    public String getShortName() { return shortName; }
    public boolean isWallet() { return wallet; }
}
