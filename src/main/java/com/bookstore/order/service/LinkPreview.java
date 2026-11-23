package com.bookstore.order.service;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Thông tin đọc được từ link sách của trang khác.
 *
 * @param title         tên sách (có thể null)
 * @param imageUrl      ảnh bìa (có thể null)
 * @param coverAmount   giá bìa của đúng trang đang mở, theo tiền gốc, vd 12.99 (null nếu không đọc được)
 * @param currency      loại tiền: VND, USD, GBP... (null nếu không rõ)
 * @param saleAmount    giá đang bán trên trang (cùng loại tiền, có thể null)
 * @param host          tên miền, vd amazon.com
 * @param coverPriceVnd giá bìa đã đổi ra VND (null nếu chưa đổi / loại tiền không hỗ trợ)
 * @param format        loại bìa của trang đang mở: PAPERBACK, HARDCOVER, LARGE_PRINT, EBOOK, AUDIO (null nếu không rõ)
 * @param formatPrices  giá các loại bìa in (PAPERBACK / HARDCOVER / LARGE_PRINT) hiện trong ô chọn định dạng của trang, cùng loại tiền
 * @param formatsListed true nếu trang có ô chọn định dạng đầy đủ (Hardcover / eBook / Audiobook...):
 *                      khi đó loại bìa nào không có trong formatPrices nghĩa là sách KHÔNG bán loại bìa đó
 * @param isbn          mã ISBN của đúng ấn bản trên trang (ISBN-13 nếu có), dùng để phân biệt loại bìa; null nếu không thấy
 */
public record LinkPreview(String title, String imageUrl, BigDecimal coverAmount, String currency,
                          BigDecimal saleAmount, String host, Long coverPriceVnd,
                          String format, Map<String, BigDecimal> formatPrices, boolean formatsListed,
                          String isbn) {

    public LinkPreview {
        formatPrices = formatPrices == null ? Map.of() : Map.copyOf(formatPrices);
    }

    public boolean hasPrice() {
        return coverPriceVnd != null && coverPriceVnd > 0;
    }

    public LinkPreview withVnd(Long vnd) {
        return new LinkPreview(title, imageUrl, coverAmount, currency, saleAmount, host, vnd, format, formatPrices, formatsListed, isbn);
    }

    public static LinkPreview empty(String host) {
        return new LinkPreview(null, null, null, null, null, host, null, null, Map.of(), false, null);
    }
}
