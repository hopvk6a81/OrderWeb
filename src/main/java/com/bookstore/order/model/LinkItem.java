package com.bookstore.order.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** Một cuốn sách khách gửi bằng link (Tiki, Fahasa, nhà xuất bản...) */
@Embeddable
public class LinkItem {

    @Column(length = 2000)
    private String url;

    private String title;

    @Column(length = 1000)
    private String imageUrl;

    /** Giá bìa theo tiền gốc của trang web, vd 12.99 */
    @Column(precision = 14, scale = 2)
    private java.math.BigDecimal originalPrice;

    /** Loại tiền của giá gốc: VND, USD, GBP */
    @Column(length = 3)
    private String currency;

    /** Giá bìa đã đổi ra VND */
    private Long coverPrice;

    /** Giá order 1 cuốn (VND) = giá bìa VND × 85% */
    private Long unitPrice;

    /** true: giá đọc tự động từ trang web; false: giá do cửa hàng nhập (hoặc chưa có giá) */
    private boolean autoPrice;

    /** Loại bìa khách chọn */
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private CoverType coverType;

    private int quantity = 1;

    /** ISBN-13 của ấn bản trên trang (giúp cửa hàng đặt đúng loại bìa) */
    @Column(length = 13)
    private String isbn;

    public LinkItem() {}

    public LinkItem(String url, String title, String imageUrl, java.math.BigDecimal originalPrice, String currency,
                    Long coverPrice, Long unitPrice, boolean autoPrice, CoverType coverType, int quantity) {
        this.coverType = coverType;
        this.url = url;
        this.title = title;
        this.imageUrl = imageUrl;
        this.originalPrice = originalPrice;
        this.currency = currency;
        this.coverPrice = coverPrice;
        this.unitPrice = unitPrice;
        this.autoPrice = autoPrice;
        this.quantity = quantity;
    }

    public long getLineTotal() {
        return unitPrice == null ? 0 : unitPrice * quantity;
    }

    /** Tên hiển thị: tên sách nếu có, nếu không thì tên miền của link */
    public String getDisplayName() {
        if (title != null && !title.isBlank()) return title;
        try {
            String host = java.net.URI.create(url).getHost();
            return host == null ? url : host.replaceFirst("^www\\.", "");
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    /** Giá gốc để hiển thị: $12.99 / £9.99 / 120.000₫ */
    public String getOriginalDisplay() {
        return com.bookstore.order.service.CurrencyRates.format(originalPrice, currency);
    }

    /** Loại tiền gợi ý khi cửa hàng nhập giá: theo giá đã đọc, nếu chưa có thì theo tên miền */
    public String getSuggestedCurrency() {
        if (currency != null) return currency;
        String h = getHost();
        if (h.endsWith(".uk")) return "GBP";
        if (h.endsWith(".vn")) return "VND";
        return "USD";
    }

    public boolean isPricePending() {
        return coverPrice == null;
    }

    public boolean isForeign() {
        return currency != null && !"VND".equalsIgnoreCase(currency);
    }

    public String getHost() {
        try {
            String host = java.net.URI.create(url).getHost();
            return host == null ? "" : host.replaceFirst("^www\\.", "");
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    public String getIsbn() { return isbn; }
    public void setIsbn(String isbn) { this.isbn = isbn; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public java.math.BigDecimal getOriginalPrice() { return originalPrice; }
    public void setOriginalPrice(java.math.BigDecimal originalPrice) { this.originalPrice = originalPrice; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public Long getCoverPrice() { return coverPrice; }
    public void setCoverPrice(Long coverPrice) { this.coverPrice = coverPrice; }
    public Long getUnitPrice() { return unitPrice; }
    public void setUnitPrice(Long unitPrice) { this.unitPrice = unitPrice; }
    public boolean isAutoPrice() { return autoPrice; }
    public void setAutoPrice(boolean autoPrice) { this.autoPrice = autoPrice; }
    public CoverType getCoverType() { return coverType; }
    public void setCoverType(CoverType coverType) { this.coverType = coverType; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
}
