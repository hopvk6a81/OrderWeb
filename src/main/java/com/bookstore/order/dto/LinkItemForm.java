package com.bookstore.order.dto;

import jakarta.validation.constraints.*;

/**
 * Một ô link sách khách nhập. Khách KHÔNG nhập giá:
 * giá bìa luôn do máy chủ đọc từ trang web (hoặc cửa hàng báo giá sau) để tránh giả mạo.
 */
public class LinkItemForm {

    @NotBlank(message = "Please paste the book link")
    @Pattern(regexp = "^https?://\\S+$", message = "Please paste a valid link (starting with http:// or https://)")
    @Size(max = 2000, message = "Link is too long")
    private String url;

    @Size(max = 300)
    private String title;

    @NotNull(message = "Please choose a cover type")
    private com.bookstore.order.model.CoverType coverType = com.bookstore.order.model.CoverType.PAPERBACK;

    @Min(value = 1, message = "Min 1")
    @Max(value = 99, message = "Max 99")
    private int quantity = 1;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public com.bookstore.order.model.CoverType getCoverType() { return coverType; }
    public void setCoverType(com.bookstore.order.model.CoverType coverType) { this.coverType = coverType; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
}
