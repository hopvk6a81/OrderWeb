package com.bookstore.order.service;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thông tin lấy được từ chính đường link, dùng khi trang chặn đọc:
 *  - mã ISBN trong link -> ảnh bìa từ Open Library (thư viện sách mở, miễn phí)
 *  - tên sách đoán từ đường dẫn, vd /w/of-mice-and-men_john-steinbeck/247566 -> "Of Mice And Men"
 */
public final class UrlInfo {

    private UrlInfo() {}

    private static final Pattern ISBN13 = Pattern.compile("(?<![0-9])(97[89][0-9]{10})(?![0-9])");
    private static final Pattern ISBN10 = Pattern.compile("(?:isbn|/dp/|/product/|ean=|isbn=)[/=]?([0-9]{9}[0-9Xx])(?![0-9])",
            Pattern.CASE_INSENSITIVE);

    public static String isbnOf(String url) {
        if (url == null) return null;
        Matcher m = ISBN13.matcher(url);
        if (m.find()) return m.group(1);
        Matcher m10 = ISBN10.matcher(url);
        return m10.find() ? m10.group(1).toUpperCase(Locale.ROOT) : null;
    }

    /** Ảnh bìa theo ISBN từ Open Library (trả lỗi 404 nếu không có ảnh -> trang tự ẩn ảnh) */
    public static String coverImageForIsbn(String isbn) {
        return isbn == null ? null : "https://covers.openlibrary.org/b/isbn/" + isbn + "-L.jpg?default=false";
    }

    private static final Pattern NOISE = Pattern.compile(
            "(?i)\\b(paperback|hardcover|hardback|ebook|kindle|edition|audiobook|large print|book|books|product|detail|dp|p|w|"
                    + "isbn|ean|html?|php|aspx?)\\b");

    /** Đoán tên sách từ đường dẫn (đoạn dài nhất có chữ, nối bằng - hoặc _) */
    public static String titleFromUrl(String url) {
        try {
            String path = URI.create(url.trim()).getRawPath();
            if (path == null) return null;
            String best = null;
            for (String part : path.split("/")) {
                String seg = URLDecoder.decode(part, StandardCharsets.UTF_8).replaceAll("\\.[a-z]{2,5}$", "");
                if (!seg.matches(".*[A-Za-zÀ-ỹ]{3,}.*") || !seg.matches(".*[-_].*")) continue;
                if (best == null || seg.length() > best.length()) best = seg;
            }
            if (best == null) return null;
            // "of-mice-and-men_john-steinbeck" -> phần trước dấu _ thường là tên sách, sau là tác giả
            String main = best.contains("_") ? best.substring(0, best.indexOf('_')) : best;
            String t = main.replaceAll("[0-9]{9,13}[Xx]?", " ").replace('-', ' ').replace('_', ' ')
                    .replaceAll("(?i)\\b[a-z]?[0-9]{5,}\\b", " ");   // mã sản phẩm kiểu p45884185
            t = NOISE.matcher(t).replaceAll(" ").replaceAll("\\s+", " ").trim();
            if (t.length() < 3) return null;
            StringBuilder sb = new StringBuilder();
            for (String w : t.split(" ")) {
                if (w.isEmpty()) continue;
                sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
            }
            String r = sb.toString().trim();
            return r.length() > 120 ? r.substring(0, 120) : r;
        } catch (Exception e) {
            return null;
        }
    }
}
