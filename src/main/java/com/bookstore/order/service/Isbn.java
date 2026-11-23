package com.bookstore.order.service;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tìm và kiểm tra mã ISBN trên trang sách.
 * Mỗi loại bìa (bìa mềm, bìa cứng, khổ lớn) có ISBN riêng, nên ISBN cho biết khách đang mở đúng ấn bản nào.
 * Luôn trả về ISBN-13 (đổi từ ISBN-10 nếu trang chỉ có mã 10 số), đã kiểm tra số cuối (check digit).
 */
public final class Isbn {

    private Isbn() {}

    /** "isbn": "978..." trong dữ liệu JSON-LD / JSON của trang (ưu tiên isbn13 nếu có) */
    private static final Pattern JSON_ISBN = Pattern.compile(
            "[\"'](?:isbn13|isbn_13|isbn|gtin13|ean)[\"']\\s*:\\s*[\"']?([0-9][0-9\\- ]{8,16}[0-9Xx])",
            Pattern.CASE_INSENSITIVE);

    /** itemprop="isbn" content="..." hoặc thẻ chứa chữ */
    private static final Pattern ITEMPROP_ISBN = Pattern.compile(
            "itemprop\\s*=\\s*[\"']isbn[\"'][^>]*?(?:content\\s*=\\s*[\"']([0-9Xx\\- ]{10,17})[\"'][^>]*>|>\\s*([0-9Xx\\- ]{10,17})\\s*<)",
            Pattern.CASE_INSENSITIVE);

    /** Chữ "ISBN-13: 978-..." / "ISBN: ..." trên trang, cho phép thẻ HTML xen giữa (vd mục chi tiết của Amazon) */
    private static final Pattern LABEL_ISBN = Pattern.compile(
            "ISBN(?:[\\s\\-]?(1[03]))?(?:\\s|<[^>]{0,200}>|&[a-zA-Z]{2,8};|&#[0-9]+;|[:：\\u200e\\u200f])*([0-9][0-9\\- ]{8,16}[0-9Xx])",
            Pattern.CASE_INSENSITIVE);

    public static String find(String html, List<Map<String, String>> metas) {
        if (html == null) return null;
        // 1. Thẻ meta: books:isbn, book:isbn, og:book:isbn...
        for (Map<String, String> m : metas) {
            String key = (m.getOrDefault("property", m.getOrDefault("name", m.getOrDefault("itemprop", ""))))
                    .toLowerCase(java.util.Locale.ROOT);
            if (key.endsWith("isbn") || key.endsWith("isbn13") || key.equals("gtin13")) {
                String v = normalize(m.get("content"));
                if (v != null) return v;
            }
        }
        // 2. Dữ liệu có cấu trúc
        String v = first(JSON_ISBN, html, 1);
        if (v != null) return v;
        Matcher im = ITEMPROP_ISBN.matcher(html);
        while (im.find()) {
            v = normalize(im.group(1) != null ? im.group(1) : im.group(2));
            if (v != null) return v;
        }
        // 3. Chữ trên trang: ưu tiên ISBN-13
        String any = null;
        Matcher lm = LABEL_ISBN.matcher(html);
        while (lm.find()) {
            String raw = lm.group(2);
            String digits = raw.replaceAll("[^0-9Xx]", "");
            String n = normalize(digits);
            if (n == null) continue;
            if (digits.length() == 13) return n;
            if (any == null) any = n;
        }
        return any;
    }

    private static String first(Pattern p, String html, int group) {
        Matcher m = p.matcher(html);
        while (m.find()) {
            String v = normalize(m.group(group));
            if (v != null) return v;
        }
        return null;
    }

    /** Chuẩn hóa về ISBN-13 hợp lệ, hoặc null nếu không phải ISBN */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.replaceAll("[^0-9Xx]", "").toUpperCase(java.util.Locale.ROOT);
        if (s.length() == 13 && (s.startsWith("978") || s.startsWith("979")) && valid13(s)) return s;
        if (s.length() == 10 && valid10(s)) return to13(s);
        return null;
    }

    static boolean valid13(String s) {
        if (!s.matches("[0-9]{13}")) return false;
        int sum = 0;
        for (int i = 0; i < 12; i++) sum += (s.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        return (10 - sum % 10) % 10 == s.charAt(12) - '0';
    }

    static boolean valid10(String s) {
        if (!s.matches("[0-9]{9}[0-9X]")) return false;
        int sum = 0;
        for (int i = 0; i < 10; i++) {
            int d = s.charAt(i) == 'X' ? 10 : s.charAt(i) - '0';
            sum += d * (10 - i);
        }
        return sum % 11 == 0;
    }

    static String to13(String isbn10) {
        String core = "978" + isbn10.substring(0, 9);
        int sum = 0;
        for (int i = 0; i < 12; i++) sum += (core.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        return core + ((10 - sum % 10) % 10);
    }
}
