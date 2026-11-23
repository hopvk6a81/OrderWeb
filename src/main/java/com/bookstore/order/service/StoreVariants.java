package com.bookstore.order.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Đọc các phiên bản (variant) của sách trên trang làm bằng nền tảng bán hàng phổ biến,
 * mỗi phiên bản thường là một loại bìa (Paperback / Hardback / eBook) có giá và ISBN riêng:
 *  - Shopify (vd nyrb.com): dữ liệu "variants" trong trang, giá tính bằng XU (1895 = 18.95)
 *  - WooCommerce / WordPress (vd faber.co.uk): thuộc tính data-product_variations
 */
public final class StoreVariants {

    private StoreVariants() {}

    /**
     * @param format   PAPERBACK / HARDCOVER / LARGE_PRINT / EBOOK / AUDIO (null nếu không rõ)
     * @param price    giá đang bán
     * @param regular  giá gốc (giá bìa) nếu trang có ghi, null nếu không giảm giá
     * @param isbn     ISBN-13 (từ sku / barcode), có thể null
     * @param id       mã phiên bản (để khớp ?variant=... trong link Shopify)
     */
    public record Variant(String format, BigDecimal price, BigDecimal regular, String isbn, String id) {
        /** Giá bìa của phiên bản: giá gốc nếu có (và lớn hơn giá bán), không thì giá bán */
        public BigDecimal coverPrice() {
            return regular != null && regular.compareTo(price) > 0 ? regular : price;
        }
    }

    public static boolean isShopify(String html) {
        return html != null && (html.contains("cdn.shopify.com") || html.contains("Shopify.shop") || html.contains("ShopifyAnalytics"));
    }

    private static final Pattern SHOPIFY_CURRENCY = Pattern.compile("Shopify\\.currency\\s*=\\s*\\{\\s*\"active\"\\s*:\\s*\"([A-Za-z]{3})\"");

    /** Loại tiền đang hiện trên trang Shopify (Shopify.currency.active) */
    public static String shopifyCurrency(String html) {
        if (html == null) return null;
        Matcher m = SHOPIFY_CURRENCY.matcher(html);
        return m.find() ? m.group(1).toUpperCase(Locale.ROOT) : null;
    }

    /** Các phiên bản đọc được (rỗng nếu trang không phải Shopify / WooCommerce hoặc không có dữ liệu) */
    public static List<Variant> find(String html) {
        if (html == null) return List.of();
        List<Variant> v = isShopify(html) ? shopify(html, true) : List.of();
        if (v.isEmpty()) v = wooCommerce(html);
        return v;
    }

    // ------------------------------------------------------------------ Shopify

    private static final Pattern VARIANTS_KEY = Pattern.compile("\"variants\"\\s*:\\s*\\[");

    /**
     * @param cents true khi số nguyên là xu (dữ liệu trong trang và /products/x.js);
     *              chuỗi có dấu chấm ("18.95", kiểu /products/x.json) luôn hiểu là tiền chẵn
     */
    static List<Variant> shopify(String text, boolean cents) {
        Matcher k = VARIANTS_KEY.matcher(text);
        while (k.find()) {
            String array = balanced(text, k.end() - 1, '[', ']');
            if (array == null) continue;
            List<Variant> out = new ArrayList<>();
            for (String obj : topLevelObjects(array)) {
                BigDecimal price = shopifyMoney(field(obj, "price"), cents);
                if (price == null) continue;
                String title = firstNonNull(field(obj, "public_title"), field(obj, "title"), field(obj, "option1"), field(obj, "name"));
                String isbn = firstNonNull(Isbn.normalize(field(obj, "barcode")), Isbn.normalize(field(obj, "sku")));
                out.add(new Variant(LinkPreviewParser.normalizeFormat(title), price,
                        shopifyMoney(field(obj, "compare_at_price"), cents), isbn, field(obj, "id")));
            }
            if (!out.isEmpty()) return out;
        }
        return List.of();
    }

    private static BigDecimal shopifyMoney(String raw, boolean cents) {
        if (raw == null || raw.isBlank() || raw.equals("null")) return null;
        try {
            if (raw.contains(".")) return new BigDecimal(raw).setScale(2, RoundingMode.HALF_UP);
            BigDecimal v = new BigDecimal(raw);
            return (cents ? v.movePointLeft(2) : v).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ WooCommerce

    private static final Pattern WOO_ATTR = Pattern.compile("data-product_variations\\s*=\\s*\"([^\"]+)\"");

    static List<Variant> wooCommerce(String html) {
        Matcher m = WOO_ATTR.matcher(html);
        if (!m.find()) return List.of();
        String json = LinkPreviewParser.unescape(m.group(1));
        List<Variant> out = new ArrayList<>();
        for (String obj : topLevelObjects(json)) {
            String attrs = balancedAfter(obj, "\"attributes\"");
            BigDecimal price = decimal(field(obj, "display_price"));
            if (price == null) continue;
            String isbn = Isbn.normalize(field(obj, "sku"));
            out.add(new Variant(LinkPreviewParser.normalizeFormat(attrs == null ? "" : attrs.replace('-', ' ')),
                    price, decimal(field(obj, "display_regular_price")), isbn, field(obj, "variation_id")));
        }
        return out;
    }

    // ------------------------------------------------------------------ tiện ích JSON đơn giản

    /** Giá trị của một khóa ở cấp ngoài cùng của object JSON (chuỗi hoặc số), null nếu không có */
    static String field(String obj, String key) {
        int depth = 0;
        boolean inStr = false;
        String needle = "\"" + key + "\"";
        for (int i = 0; i < obj.length(); i++) {
            char c = obj.charAt(i);
            if (inStr) {
                if (c == '\\') i++;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') depth--;
            else if (c == '"') {
                if (depth == 1 && obj.startsWith(needle, i)) {
                    int j = i + needle.length();
                    while (j < obj.length() && Character.isWhitespace(obj.charAt(j))) j++;
                    if (j < obj.length() && obj.charAt(j) == ':') return value(obj, j + 1);
                }
                inStr = true;
            }
        }
        return null;
    }

    private static String value(String s, int from) {
        int i = from;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        if (i >= s.length()) return null;
        if (s.charAt(i) == '"') {
            StringBuilder sb = new StringBuilder();
            for (int j = i + 1; j < s.length(); j++) {
                char c = s.charAt(j);
                if (c == '\\' && j + 1 < s.length()) { sb.append(s.charAt(++j)); continue; }
                if (c == '"') return sb.toString();
                sb.append(c);
            }
            return null;
        }
        int j = i;
        while (j < s.length() && ",}] \n\r\t".indexOf(s.charAt(j)) < 0) j++;
        return s.substring(i, j);
    }

    private static String balancedAfter(String obj, String key) {
        int i = obj.indexOf(key);
        if (i < 0) return null;
        int b = obj.indexOf('{', i);
        return b < 0 ? null : balanced(obj, b, '{', '}');
    }

    /** Đoạn từ dấu mở tại vị trí start tới dấu đóng tương ứng (bỏ qua ký tự trong chuỗi) */
    static String balanced(String s, int start, char open, char close) {
        int depth = 0;
        boolean inStr = false;
        for (int i = start; i < s.length() && i < start + 400_000; i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') i++;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == open) depth++;
            else if (c == close && --depth == 0) return s.substring(start, i + 1);
        }
        return null;
    }

    /** Các object {...} nằm trực tiếp trong một mảng [...] (chuỗi phải bắt đầu bằng '[') */
    static List<String> topLevelObjects(String array) {
        List<String> out = new ArrayList<>();
        int depth = 0, begin = -1;
        boolean inStr = false;
        for (int i = 0; i < array.length(); i++) {
            char c = array.charAt(i);
            if (inStr) {
                if (c == '\\') i++;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == '[' || c == '{') {
                if (c == '{' && depth == 1) begin = i;
                depth++;
            } else if (c == ']' || c == '}') {
                depth--;
                if (c == '}' && depth == 1 && begin >= 0) {
                    out.add(array.substring(begin, i + 1));
                    begin = -1;
                }
                if (depth == 0) break;
            }
        }
        return out;
    }

    private static BigDecimal decimal(String raw) {
        if (raw == null || raw.isBlank() || raw.equals("null")) return null;
        try {
            return new BigDecimal(raw.trim()).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) if (v != null && !(v instanceof String s && s.isBlank())) return v;
        return null;
    }
}
