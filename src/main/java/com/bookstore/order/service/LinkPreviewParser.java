package com.bookstore.order.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Đọc tên sách, ảnh bìa, giá bìa và LOẠI TIỀN (VND / USD / GBP / EUR...) từ mã HTML
 * của một trang bán sách bất kỳ. Chỉ dùng thư viện chuẩn của Java.
 *
 * Giá bìa (giá gốc chưa giảm) được tìm theo thứ tự:
 *   1. Chữ "Giá bìa: 120.000đ", "RRP £14.99", "List Price: $27.00"
 *   2. Giá gạch ngang của Amazon (data-a-strike)
 *   3. Dữ liệu JSON trong trang: list_price, original_price, listPrice, regular_price...
 *   4. Thẻ HTML có class old-price, original-price, list-price, rrp, was-price... hoặc thẻ <del>/<s>
 * Nếu không có giá gốc thì lấy giá đang bán (meta product:price, JSON-LD, Amazon a-price...).
 */
public final class LinkPreviewParser {

    private LinkPreviewParser() {}

    /** Một số tiền kèm loại tiền */
    public record Money(BigDecimal amount, String currency) {}

    public static LinkPreview parse(String html, String pageUrl) {
        String host = hostOf(pageUrl);
        if (html == null || html.isBlank()) return LinkPreview.empty(host);

        html = joinSplitMoney(html);
        List<Map<String, String>> metas = readMetaTags(html);
        String title = firstNonBlank(
                meta(metas, "og:title"), meta(metas, "twitter:title"), productTitle(html), tagText(html, "title"));
        String image = firstNonBlank(
                meta(metas, "og:image:secure_url"), meta(metas, "og:image"), meta(metas, "twitter:image"),
                meta(metas, "twitter:image:src"), linkHref(html, "image_src"), jsonImage(html), amazonImage(html));

        String pageCurrency = pageCurrency(html, metas, host);
        String shopifyCur = StoreVariants.shopifyCurrency(html);   // loại tiền Shopify đang hiện cho khách
        if (shopifyCur != null) pageCurrency = shopifyCur;
        Money cover = findCover(html, pageCurrency);
        Money sale = findSale(html, metas, pageCurrency);

        if (cover != null && sale != null && cover.currency().equals(sale.currency())
                && sale.amount().compareTo(cover.amount()) > 0) {
            cover = null; // "giá gốc" nhỏ hơn giá bán -> đọc nhầm, bỏ
        }
        if (cover == null) cover = sale; // sách không giảm giá -> giá bìa = giá bán

        String cur = cover != null ? cover.currency() : pageCurrency;
        FormatInfo fi = formatInfo(html, cur, cover, sale);
        String format = pageFormat(html, title);
        if (format == null) format = fi.nearPriceFormat();
        if (format == null && fi.listed()) {
            // Ô định dạng chỉ có đúng 1 loại bìa in -> trang đang mở là loại đó
            if (fi.prices().size() == 1) format = fi.prices().keySet().iterator().next();
        }

        // Trang Shopify / WooCommerce: dùng dữ liệu phiên bản (mỗi loại bìa một giá, một ISBN) – chính xác hơn đọc chữ
        String isbn = null;
        List<StoreVariants.Variant> variants = StoreVariants.find(html);
        if (!variants.isEmpty() && pageCurrency != null) {
            StoreVariants.Variant sel = selectedVariant(variants, pageUrl);
            if (plausible(sel.price(), pageCurrency)) {
                cover = new Money(sel.coverPrice(), pageCurrency);
                sale = new Money(sel.price(), pageCurrency);
                Map<String, BigDecimal> prices = new LinkedHashMap<>();
                boolean anyFormat = false;
                for (StoreVariants.Variant v : variants) {
                    if (v.format() == null) continue;
                    anyFormat = true;
                    if (PRINT_FORMATS.contains(v.format()) && plausible(v.coverPrice(), pageCurrency)) {
                        prices.merge(v.format(), v.coverPrice(), (x, y) -> x.compareTo(y) <= 0 ? x : y);
                    }
                }
                if (anyFormat) fi = new FormatInfo(prices, true, sel.format());
                if (sel.format() != null) format = sel.format();
                isbn = sel.isbn();
            }
        }
        if (isbn == null) isbn = Isbn.find(html, metas);

        String cleanTitle = clean(title);
        if (AmazonHelper.isJunkTitle(cleanTitle)) cleanTitle = null;
        cleanTitle = stripSiteName(cleanTitle, meta(metas, "og:site_name"));
        return new LinkPreview(cleanTitle, resolve(pageUrl, unescape(image)),
                cover == null ? null : cover.amount(), cover == null ? null : cover.currency(),
                sale == null ? null : sale.amount(), host, null, format, fi.prices(), fi.listed(), isbn);
    }

    /** Dữ liệu /products/ten-sach.js của Shopify (giá tính bằng xu) -> thông tin sách */
    static LinkPreview fromShopifyJson(String json, String pageUrl, String host, String currency) {
        if (json == null || currency == null) return null;
        List<StoreVariants.Variant> variants = StoreVariants.shopify(json, true);
        if (variants.isEmpty()) return null;
        StoreVariants.Variant sel = selectedVariant(variants, pageUrl);
        if (!plausible(sel.price(), currency)) return null;
        Map<String, BigDecimal> prices = new LinkedHashMap<>();
        boolean anyFormat = false;
        for (StoreVariants.Variant v : variants) {
            if (v.format() == null) continue;
            anyFormat = true;
            if (PRINT_FORMATS.contains(v.format()) && plausible(v.coverPrice(), currency)) {
                prices.merge(v.format(), v.coverPrice(), (x, y) -> x.compareTo(y) <= 0 ? x : y);
            }
        }
        String title = StoreVariants.field(json, "title");
        String image = StoreVariants.field(json, "featured_image");
        if (image != null && image.startsWith("//")) image = "https:" + image;
        return new LinkPreview(title == null ? null : clean(title), image, sel.coverPrice(), currency, sel.price(), host,
                null, sel.format(), anyFormat ? prices : Map.of(), anyFormat, sel.isbn());
    }

    /** "Sleep Gate - Fitzcarraldo Editions" -> "Sleep Gate" (bỏ tên website ở cuối tên sách) */
    static String stripSiteName(String title, String siteName) {
        if (title == null || siteName == null || siteName.isBlank()) return title;
        String site = clean(siteName);
        if (site == null) return title;
        for (String sep : new String[]{" - ", " – ", " | ", " — ", " :: "}) {
            String tail = sep + site;
            if (title.length() > tail.length() + 1 && title.toLowerCase(Locale.ROOT).endsWith(tail.toLowerCase(Locale.ROOT))) {
                return title.substring(0, title.length() - tail.length()).trim();
            }
        }
        return title;
    }

    private static final java.util.Set<String> PRINT_FORMATS = java.util.Set.of("PAPERBACK", "HARDCOVER", "LARGE_PRINT");

    /** Phiên bản khách đang mở: theo ?variant=... trong link, không thì phiên bản sách in đầu tiên */
    static StoreVariants.Variant selectedVariant(List<StoreVariants.Variant> variants, String pageUrl) {
        Matcher m = Pattern.compile("[?&]variant=(\\d+)").matcher(pageUrl == null ? "" : pageUrl);
        if (m.find()) {
            for (StoreVariants.Variant v : variants) if (m.group(1).equals(v.id())) return v;
        }
        for (StoreVariants.Variant v : variants) if (v.format() != null && PRINT_FORMATS.contains(v.format())) return v;
        return variants.get(0);
    }

    /** Ký hiệu tiền và con số bị tách bởi thẻ HTML ("&pound;</span>10.99" của WooCommerce) -> ghép lại "£10.99" */
    private static final Pattern SPLIT_MONEY = Pattern.compile(
            "(£|&pound;|&#163;|€|&euro;|\\$)(?:\\s|&nbsp;|<[^<>]{0,150}>)+(?=[0-9])");

    static String joinSplitMoney(String html) {
        return SPLIT_MONEY.matcher(html).replaceAll("$1");
    }

    // =========================================================== loại bìa

    /** Chuẩn hóa tên định dạng sách -> PAPERBACK / HARDCOVER / EBOOK / AUDIO (null nếu không nhận ra) */
    public static String normalizeFormat(String text) {
        if (text == null) return null;
        String t = unescape(text).toLowerCase(Locale.ROOT);
        if (t.matches("(?s).*(large print|large type).*")) return "LARGE_PRINT";
        if (t.matches("(?s).*(nook book|\\bebook\\b|e-book).*")) return "EBOOK";
        if (t.matches("(?s).*(hardcover|hardback|hard cover|bìa cứng|bia cung|library binding).*")) return "HARDCOVER";
        if (t.matches("(?s).*(paperback|softcover|soft cover|trade paper|mass market|bìa mềm|bia mem|perfect bound).*")) return "PAPERBACK";
        if (t.matches("(?s).*(kindle|ebook|e-book|epub|digital).*")) return "EBOOK";
        if (t.matches("(?s).*(audiobook|audible|audio cd|audio book|mp3 cd).*")) return "AUDIO";
        return null;
    }

    private static final Pattern FORMAT_LABEL = Pattern.compile(
            "(?:id\\s*=\\s*[\"'](?:productSubtitle|productBinding|bookEdition|format-type)[\"'][^>]*>"
                    + "|[\"']bookFormat[\"']\\s*:\\s*[\"']"
                    + "|\\b(?:Format|Binding|Book\\s*Format)\\s*:" + LinkPreviewParser.TAGS
                    + "|Lo[ạa]i\\s*b[ìi]a\\s*:?" + LinkPreviewParser.TAGS
                    + "|H[ìi]nh\\s*th[ứu]c\\s*:?" + LinkPreviewParser.TAGS + ")"
                    + "([^<\"]{2,80})",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern SELECTED_SWATCH = Pattern.compile(
            "class\\s*=\\s*[\"'][^\"']*swatchElement[^\"']*selected[^\"']*[\"'][^>]*>(.{0,600}?)</(?:a|span)>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Loại bìa của trang đang mở */
    static String pageFormat(String html, String title) {
        Matcher m = FORMAT_LABEL.matcher(html);
        int tries = 0;
        while (m.find() && tries++ < 20) {
            String f = normalizeFormat(m.group(1));
            if (f != null) return f;
        }
        Matcher sel = SELECTED_SWATCH.matcher(html);
        if (sel.find()) {
            String f = normalizeFormat(stripTags(sel.group(1)));
            if (f != null) return f;
        }
        // Tên trang Amazon thường có dạng "... : Paperback" / "(Hardcover)"
        if (title != null) {
            Matcher t = Pattern.compile("(?i)[(:|–-]\\s*(Large Print|Paperback|Hardcover|Hardback|Mass Market Paperback|Kindle Edition)\\b").matcher(title);
            if (t.find()) return normalizeFormat(t.group(1));
        }
        return null;
    }

    private static final Pattern FORMAT_WORD = Pattern.compile(
            "\\b(Large\\s+Print(?:\\s+(?:Paperback|Hardcover))?|Mass\\s+Market\\s+Paperback|Paperback|Hardcover|Hardback"
                    + "|Kindle(?:\\s+Edition)?|NOOK\\s+Book|eBook|Audiobook|Audible\\s+Audiobook|Audio\\s+CD"
                    + "|Library\\s+Binding|Board\\s+book|Spiral-bound)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SYMBOL_MONEY = Pattern.compile("(?:US\\s?\\$|\\$|£|€)\\s?[0-9][0-9.,]*");

    /**
     * Thông tin ô chọn định dạng.
     * @param prices          giá bìa mềm / bìa cứng tìm thấy (giá thấp nhất mỗi loại)
     * @param listed          có ô chọn định dạng (thấy từ 2 định dạng trở lên) -> danh sách coi là đầy đủ
     * @param nearPriceFormat định dạng ghi ngay trước giá chính của trang (vd "Hardcover $30.00")
     */
    record FormatInfo(Map<String, BigDecimal> prices, boolean listed, String nearPriceFormat) {}

    static FormatInfo formatInfo(String html, String currency, Money cover, Money sale) {
        String text;
        int amazon = indexOfAny(html, "id=\"tmmSwatches\"", "id='tmmSwatches'", "tmm-grid-swatch", "swatchElement");
        String nearPrice = null;
        if (amazon >= 0) {
            text = stripTags(html.substring(amazon, Math.min(html.length(), amazon + 60_000)));
        } else {
            // Trang khác (Barnes & Noble, Waterstones...): xét đoạn chữ quanh giá chính
            String all = stripTags(html.replaceAll("(?is)<(script|style|noscript)\\b.*?</\\1>", " "));
            int pos = priceIndex(all, sale, currency);
            if (pos < 0) pos = priceIndex(all, cover, currency);
            if (pos < 0) return new FormatInfo(Map.of(), false, null);
            // định dạng ghi ngay trước giá (trong khoảng 60 ký tự)
            Matcher before = FORMAT_WORD.matcher(all.substring(Math.max(0, pos - 60), pos));
            while (before.find()) nearPrice = normalizeFormat(before.group(1));
            text = all.substring(Math.max(0, pos - 1500), Math.min(all.length(), pos + 2500));
        }

        // Tách các định dạng thành từng cụm liền nhau (cách nhau không quá 150 ký tự).
        // Ô chọn định dạng là cụm có nhiều định dạng nhất; các gợi ý "Customers also bought" chỉ là cụm lẻ.
        Matcher w = FORMAT_WORD.matcher(text);
        List<int[]> spots = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (w.find()) {
            spots.add(new int[]{w.start(), w.end()});
            names.add(w.group(1));
        }
        Map<String, BigDecimal> bestPrices = new LinkedHashMap<>();
        int bestCount = 0;
        int i = 0;
        while (i < spots.size()) {
            int j = i;
            while (j + 1 < spots.size() && spots.get(j + 1)[0] - spots.get(j)[1] <= 150) j++;
            Map<String, BigDecimal> prices = new LinkedHashMap<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (int k = i; k <= j; k++) {
                String f = normalizeFormat(names.get(k));
                if (f == null) continue;
                int from = spots.get(k)[1];
                int to = Math.min(k < j ? spots.get(k + 1)[0] : text.length(), from + 120);
                String between = text.substring(from, to);
                Matcher mm = SYMBOL_MONEY.matcher(between);
                boolean hasPrice = mm.find();
                boolean free = between.toLowerCase(Locale.ROOT).matches("(?s)\\s*(free|included|\\$0\\.00).*");
                if (hasPrice || free) seen.add(f);      // chỉ tính khi có giá đi kèm
                if (!hasPrice || !(f.equals("PAPERBACK") || f.equals("HARDCOVER") || f.equals("LARGE_PRINT"))) continue;
                Money money = money(mm.group(), currency);
                if (money == null || (currency != null && !currency.equals(money.currency()))) continue;
                prices.merge(f, money.amount(), (x, y) -> x.compareTo(y) <= 0 ? x : y);
            }
            if (seen.size() > bestCount) {
                bestCount = seen.size();
                bestPrices = prices;
            }
            i = j + 1;
        }
        // Chỉ có 1 định dạng lẻ thì không coi là ô chọn định dạng (tránh lấy nhầm giá sách khác)
        return bestCount >= 2 ? new FormatInfo(bestPrices, true, nearPrice)
                : new FormatInfo(Map.of(), false, nearPrice);
    }

    /** Vị trí số tiền (vd "$30.00") trong đoạn chữ */
    private static int priceIndex(String text, Money m, String currency) {
        if (m == null) return -1;
        Matcher mm = SYMBOL_MONEY.matcher(text);
        while (mm.find()) {
            Money v = money(mm.group(), currency);
            if (v != null && v.currency().equals(m.currency()) && v.amount().compareTo(m.amount()) == 0) return mm.start();
        }
        return -1;
    }

    private static int indexOfAny(String s, String... needles) {
        int best = -1;
        for (String n : needles) {
            int i = s.indexOf(n);
            if (i >= 0 && (best < 0 || i < best)) best = i;
        }
        return best;
    }

    // =========================================================== loại tiền

    private static final Pattern LD_JSON = Pattern.compile(
            "<script[^>]+application/ld\\+json[^>]*>(.*?)</script>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern PRICE_CURRENCY = Pattern.compile("[\"']priceCurrency[\"']\\s*:\\s*[\"']([A-Za-z]{3})");
    private static final Pattern JSON_CURRENCY = Pattern.compile(
            "[\"'](?:currency|currencyCode|currency_code|priceCurrency)[\"']\\s*:\\s*[\"']([A-Za-z]{3})[\"']");

    /** Loại tiền chung của trang (dùng khi con số không kèm ký hiệu tiền) */
    static String pageCurrency(String html, List<Map<String, String>> metas, String host) {
        String c = firstNonBlank(meta(metas, "product:price:currency"), meta(metas, "og:price:currency"),
                meta(metas, "priceCurrency"));
        if (c == null) {
            Matcher ld = LD_JSON.matcher(html);
            while (c == null && ld.find()) {
                Matcher m = PRICE_CURRENCY.matcher(ld.group(1));
                if (m.find()) c = m.group(1);
            }
        }
        if (c == null) {
            Matcher m = JSON_CURRENCY.matcher(html);
            if (m.find()) c = m.group(1);
        }
        if (c == null && host != null) {
            if (host.endsWith(".uk")) c = "GBP";
            else if (host.endsWith(".vn")) c = "VND";
            else if (host.matches("(.+\\.)?(amazon\\.com|barnesandnoble\\.com|bookshop\\.org|booksamillion\\.com|powells\\.com|target\\.com|walmart\\.com)")) c = "USD";
        }
        return c == null ? null : c.toUpperCase(Locale.ROOT);
    }

    /** Nhận ra loại tiền từ ký hiệu trong chuỗi */
    static String currencyIn(String s) {
        if (s == null) return null;
        String t = s.toLowerCase(Locale.ROOT);
        if (t.contains("£") || t.contains("gbp") || t.contains("&pound;") || t.contains("&#163;")) return "GBP";
        if (t.contains("€") || t.contains("eur") || t.contains("&euro;")) return "EUR";
        if (t.contains("₫") || t.contains("vnd") || t.contains("vnđ") || t.contains("&#8363;")
                || t.matches("(?s).*[0-9]\\s*(?:đ|&nbsp;đ).*")) return "VND";
        if (t.contains("a$") || t.contains("aud")) return "AUD";
        if (t.contains("c$") || t.contains("cad")) return "CAD";
        if (t.contains("$") || t.contains("usd")) return "USD";
        if (t.contains("¥") || t.contains("jpy")) return "JPY";
        return null;
    }

    // =========================================================== số tiền

    /**
     * Đổi chuỗi tiền sang số, có xét loại tiền.
     * VND: "120.000" "120,000" "120000" "1.250.000đ".   USD/GBP: "$12.99" "£1,299.00" "14.99".
     */
    static Money money(String raw, String fallbackCurrency) {
        if (raw == null) return null;
        String cur = currencyIn(raw);
        if (cur == null) cur = fallbackCurrency;
        Matcher num = Pattern.compile("[0-9][0-9.,\\s]*[0-9]|[0-9]").matcher(raw.replace("&nbsp;", " "));
        if (!num.find()) return null;
        String s = num.group().replaceAll("\\s", "");

        BigDecimal value;
        try {
            boolean zeroDecimal = cur == null || cur.equals("VND") || cur.equals("JPY");
            if (s.matches("\\d{1,3}([.,]\\d{3})+") && (zeroDecimal || !s.matches("\\d{1,3}[.,]\\d{3}"))) {
                // 120.000 / 1,250,000 -> phân cách hàng nghìn
                value = new BigDecimal(s.replaceAll("[.,]", ""));
            } else if (s.matches("\\d{1,3}(,\\d{3})+\\.\\d{1,2}")) {
                value = new BigDecimal(s.replace(",", ""));                       // 1,299.99
            } else if (s.matches("\\d{1,3}(\\.\\d{3})+,\\d{1,2}")) {
                value = new BigDecimal(s.replace(".", "").replace(',', '.'));     // 1.299,99
            } else if (s.matches("\\d+[.,]\\d{1,3}")) {
                value = new BigDecimal(s.replace(',', '.'));                     // 12.99 / 12,99 / 120000.0
            } else if (s.matches("\\d+")) {
                value = new BigDecimal(s);
            } else {
                return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }

        if (cur == null) {
            // Không rõ loại tiền: số lớn, không lẻ -> coi là VND
            if (value.compareTo(BigDecimal.valueOf(1000)) >= 0 && value.stripTrailingZeros().scale() <= 0) cur = "VND";
            else return null;
        }
        if (!plausible(value, cur)) return null;
        int scale = (cur.equals("VND") || cur.equals("JPY")) ? 0 : 2;
        return new Money(value.setScale(scale, RoundingMode.HALF_UP), cur);
    }

    /** Khoảng giá hợp lý cho một cuốn sách */
    public static boolean plausible(BigDecimal v, String cur) {
        double d = v.doubleValue();
        return switch (cur) {
            case "VND" -> d >= 1_000 && d <= 50_000_000;
            case "JPY" -> d >= 50 && d <= 500_000;
            default -> d >= 0.5 && d <= 5_000;
        };
    }

    // =========================================================== giá bìa

    private static final String TAGS = "(?:\\s|<[^>]*>|&nbsp;|:)*";
    private static final String AMOUNT = "((?:US\\s?\\$|\\$|£|€|&pound;|&euro;)?\\s?[0-9][0-9.,]*(?:\\s?(?:đ|₫|vnd|vnđ|&#8363;))?)";

    private static final Pattern LABELLED_COVER = Pattern.compile(
            "(?:Gi[áa]\\s*b[ìi]a|Gi[áa]\\s*g[ốo]c|RRP|List\\s*Price|Original\\s*Price|Recommended\\s*Retail\\s*Price"
                    + "|Cover\\s*Price|Retail\\s*Price|Publisher\\s*Price|MSRP|Regular\\s*Price)" + TAGS + AMOUNT,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern AMAZON_STRIKE = Pattern.compile(
            "data-a-strike\\s*=\\s*[\"']true[\"'][^>]*>(?:\\s|<[^>]*>)*?<span[^>]*a-offscreen[^>]*>([^<]+)<",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern JSON_LIST_PRICE = Pattern.compile(
            "[\"'](?:list_price|listPrice|original_price|originalPrice|price_before_discount|priceBeforeDiscount"
                    + "|regular_price|regularPrice|market_price|marketPrice|old_price|oldPrice|cover_price|coverPrice"
                    + "|price_origin|origin_price|retail_price|retailPrice|rrp|compare_at_price|compareAtPrice)[\"']"
                    + "\\s*:\\s*(\"[^\"]{1,30}\"|[0-9][0-9.]*)");

    private static final Pattern CLASS_LIST_PRICE = Pattern.compile(
            "class\\s*=\\s*[\"'][^\"']*(?:old-price|original-price|list-price|price-old|regular-price|price-original"
                    + "|price-before|compare-price|price--compare|was-price|price-was|line-through|price-del"
                    + "|origin-price|rrp|price-rrp|strike|a-text-strike|basisPrice)"
                    + "[^\"']*[\"'][^>]*>(.{0,400}?)</(?:p|div|span|del|s|strike|li|td|dd)>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern DEL_TAG = Pattern.compile(
            "<(?:del|s|strike)\\b[^>]*>(.{0,200}?)</(?:del|s|strike)>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern ID_LIST_PRICE = Pattern.compile(
            "id\\s*=\\s*[\"'](?:listPrice|list_price|priceblock_listprice|print-list-price)[\"'][^>]*>(.{0,300}?)</(?:span|div|td)>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    static Money findCover(String html, String pageCurrency) {
        Money m = firstMoney(LABELLED_COVER, html, pageCurrency, false);
        if (m != null) return m;
        m = firstMoney(ID_LIST_PRICE, html, pageCurrency, true);
        if (m != null) return m;
        m = firstMoney(AMAZON_STRIKE, html, pageCurrency, false);
        if (m != null) return m;
        m = firstMoney(JSON_LIST_PRICE, html, pageCurrency, false);
        if (m != null) return m;
        m = firstMoney(CLASS_LIST_PRICE, html, pageCurrency, true);
        if (m != null) return m;
        return firstMoney(DEL_TAG, html, pageCurrency, true);
    }

    // =========================================================== giá bán

    private static final Pattern JSON_SALE_PRICE = Pattern.compile(
            "[\"'](?:price|final_price|finalPrice|sale_price|salePrice|special_price|specialPrice|current_price"
                    + "|currentPrice|lowPrice|selling_price|priceAmount)[\"']\\s*:\\s*(\"[^\"]{1,30}\"|[0-9][0-9.]*)");

    private static final Pattern AMAZON_PRICE = Pattern.compile(
            "class\\s*=\\s*[\"']a-price(?![^\"']*a-text-price)[^\"']*[\"'][^>]*>(?:\\s|<[^>]*>)*?<span[^>]*a-offscreen[^>]*>([^<]+)<",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CLASS_SALE_PRICE = Pattern.compile(
            "class\\s*=\\s*[\"'][^\"']*(?:special-price|sale-price|current-price|final-price|product-price"
                    + "|price-current|price--sale|price-new|new-price|product__price|price-now|our-price)"
                    + "[^\"']*[\"'][^>]*>(.{0,400}?)</(?:p|div|span|ins|li|td|dd|b|strong)>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern CLASS_PRICE_BOX = Pattern.compile(
            "<(?:p|div)\\s[^>]*class\\s*=\\s*[\"'](?:[^\"']*\\s)?price(?:\\s[^\"']*)?[\"'][^>]*>(.{0,600}?)</(?:p|div)>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern LABELLED_SALE = Pattern.compile(
            "(?:Our\\s*Price|Sale\\s*Price|Your\\s*Price|Price\\s*New|New\\s*from|Buy\\s*New|\\bPrint(?:\\s*edition)?)" + LinkPreviewParser.TAGS + LinkPreviewParser.AMOUNT_SALE,
            Pattern.CASE_INSENSITIVE);
    private static final String AMOUNT_SALE = "((?:US\\s?\\$|\\$|£|€|&pound;|&euro;)\\s?[0-9][0-9.,]*)";

    static Money findSale(String html, List<Map<String, String>> metas, String pageCurrency) {
        // 1. meta product:price:amount
        String metaAmount = firstNonBlank(meta(metas, "product:price:amount"), meta(metas, "og:price:amount"),
                meta(metas, "price"));
        if (metaAmount != null) {
            String metaCur = firstNonBlank(meta(metas, "product:price:currency"), meta(metas, "og:price:currency"));
            Money m = money(metaAmount, metaCur != null ? metaCur.toUpperCase(Locale.ROOT) : pageCurrency);
            if (m != null) return m;
        }
        // 2. JSON-LD (schema.org Offer), mỗi khối có thể có priceCurrency riêng
        Matcher ld = LD_JSON.matcher(html);
        while (ld.find()) {
            String block = ld.group(1);
            Matcher cur = PRICE_CURRENCY.matcher(block);
            String c = cur.find() ? cur.group(1).toUpperCase(Locale.ROOT) : pageCurrency;
            Money m = firstMoney(JSON_SALE_PRICE, block, c, false);
            if (m != null) return m;
        }
        // 3. Amazon / chữ "Our Price: $25.20"
        Money m = firstMoney(AMAZON_PRICE, html, pageCurrency, false);
        if (m != null) return m;
        m = firstMoney(LABELLED_SALE, html, pageCurrency, false);
        if (m != null) return m;
        // 4. Thẻ có class giá bán
        m = firstMoney(CLASS_SALE_PRICE, html, pageCurrency, true);
        if (m != null) return m;
        // 4b. <p class="price"> của WooCommerce / WordPress
        m = firstMoney(CLASS_PRICE_BOX, html, pageCurrency, true);
        if (m != null) return m;
        // 4c. Số tiền ngay cạnh nút "Add to basket" / "Add to cart" / "Buy now" (vd fitzcarraldoeditions.com)
        m = nearBuyButton(html, pageCurrency);
        if (m != null) return m;
        // 5. Dữ liệu JSON khác trong trang
        return firstMoney(JSON_SALE_PRICE, html, pageCurrency, false);
    }

    private static final Pattern BUY_BUTTON = Pattern.compile(
            "(?:Add\\s+to\\s+(?:basket|cart|bag)|Buy\\s+now|Pre-?order\\s+now|Th[êe]m\\s+v[àa]o\\s+gi[ỏo]|Mua\\s+ngay)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern SYMBOL_AMOUNT = Pattern.compile("(?:US\\s?\\$|\\$|£|€)\\s?[0-9][0-9.,]*");

    /** Giá gần nút mua nhất (trong khoảng 300 ký tự chữ trước / sau nút), bỏ qua phần script / style */
    static Money nearBuyButton(String html, String pageCurrency) {
        String text = stripTags(unescape(html.replaceAll("(?is)<(script|style|noscript|header|footer|nav)\\b.*?</\\1>", " ")));
        Matcher b = BUY_BUTTON.matcher(text);
        int tries = 0;
        while (b.find() && tries++ < 5) {
            String around = text.substring(Math.max(0, b.start() - 300), Math.min(text.length(), b.end() + 300));
            int center = b.start() - Math.max(0, b.start() - 300);
            Matcher mm = SYMBOL_AMOUNT.matcher(around);
            Money best = null;
            int bestDist = Integer.MAX_VALUE;
            while (mm.find()) {
                Money v = money(mm.group(), pageCurrency);
                int dist = Math.abs(mm.start() - center);
                if (v != null && dist < bestDist) { best = v; bestDist = dist; }
            }
            if (best != null) return best;
        }
        return null;
    }

    // =========================================================== tiện ích tìm giá

    private static final Pattern MONEY_IN_TEXT = Pattern.compile(
            "(?:US\\s?\\$|\\$|£|€)\\s?[0-9][0-9.,]*|[0-9][0-9.,]*\\s?(?:đ|₫|vnd|vnđ)?", Pattern.CASE_INSENSITIVE);

    private static Money firstMoney(Pattern p, String text, String fallbackCurrency, boolean htmlBlock) {
        Matcher m = p.matcher(text);
        int tries = 0;
        while (m.find() && tries++ < 30) {
            String g = m.group(1).replace("\"", "");
            if (!htmlBlock) {
                Money v = money(unescape(g), fallbackCurrency);
                if (v != null) return v;
                continue;
            }
            String plain = stripTags(g);
            Matcher mm = MONEY_IN_TEXT.matcher(plain);
            while (mm.find()) {
                Money v = money(mm.group(), currencyIn(plain) != null ? currencyIn(plain) : fallbackCurrency);
                if (v != null) return v;
            }
        }
        return null;
    }

    // =========================================================== meta / title / image

    private static final Pattern META_TAG = Pattern.compile("<meta\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR = Pattern.compile("([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");

    static List<Map<String, String>> readMetaTags(String html) {
        List<Map<String, String>> list = new ArrayList<>();
        Matcher m = META_TAG.matcher(html);
        while (m.find()) list.add(attributes(m.group()));
        return list;
    }

    private static Map<String, String> attributes(String tag) {
        Map<String, String> map = new LinkedHashMap<>();
        Matcher a = ATTR.matcher(tag);
        while (a.find()) {
            String val = a.group(2) != null ? a.group(2) : a.group(3) != null ? a.group(3) : a.group(4);
            map.putIfAbsent(a.group(1).toLowerCase(Locale.ROOT), val);
        }
        return map;
    }

    private static String meta(List<Map<String, String>> metas, String key) {
        for (Map<String, String> m : metas) {
            String k = firstNonBlank(m.get("property"), m.get("name"), m.get("itemprop"));
            if (k != null && k.equalsIgnoreCase(key)) {
                String c = m.get("content");
                if (c != null && !c.isBlank()) return c.trim();
            }
        }
        return null;
    }

    private static String tagText(String html, String tag) {
        Matcher m = Pattern.compile("<" + tag + "\\b[^>]*>(.*?)</" + tag + ">", Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                .matcher(html);
        return m.find() ? stripTags(m.group(1)) : null;
    }

    /** Tên sách trên Amazon nằm ở <span id="productTitle"> */
    private static String productTitle(String html) {
        Matcher m = Pattern.compile("id\\s*=\\s*[\"'](?:productTitle|ebooksProductTitle)[\"'][^>]*>(.*?)</span>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        return m.find() ? stripTags(m.group(1)) : null;
    }

    private static String linkHref(String html, String rel) {
        Matcher m = Pattern.compile("<link\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(html);
        while (m.find()) {
            Map<String, String> a = attributes(m.group());
            if (rel.equalsIgnoreCase(a.get("rel"))) return a.get("href");
        }
        return null;
    }

    private static final Pattern JSON_IMAGE = Pattern.compile(
            "[\"'](?:thumbnail_url|thumbnailUrl|image_url|imageUrl|base_image)[\"']\\s*:\\s*[\"'](https?:[^\"']+)[\"']");
    private static final Pattern LD_IMAGE = Pattern.compile("[\"']image[\"']\\s*:\\s*\\[?\\s*[\"']([^\"']+)[\"']");

    private static String jsonImage(String html) {
        Matcher m = JSON_IMAGE.matcher(html);
        if (m.find()) return m.group(1).replace("\\/", "/");
        Matcher ld = LD_JSON.matcher(html);
        while (ld.find()) {
            Matcher img = LD_IMAGE.matcher(ld.group(1));
            if (img.find()) return img.group(1).replace("\\/", "/");
        }
        return null;
    }

    /** Ảnh bìa Amazon: <img id="landingImage" ... src="..."> hoặc data-old-hires */
    private static String amazonImage(String html) {
        Matcher m = Pattern.compile("<img\\b[^>]*id\\s*=\\s*[\"'](?:landingImage|imgBlkFront|ebooksImgBlkFront)[\"'][^>]*>",
                Pattern.CASE_INSENSITIVE).matcher(html);
        if (!m.find()) return null;
        Map<String, String> a = attributes(m.group());
        return firstNonBlank(a.get("data-old-hires"), a.get("src"));
    }

    // =========================================================== tiện ích chung

    static String hostOf(String url) {
        try {
            String h = URI.create(url.trim()).getHost();
            return h == null ? null : h.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        } catch (Exception e) {
            return null;
        }
    }

    private static String resolve(String base, String url) {
        if (url == null || url.isBlank() || url.startsWith("data:")) return null;
        try {
            String u = url.trim();
            if (u.startsWith("//")) u = "https:" + u;
            String s = URI.create(base).resolve(u.replace(" ", "%20")).toString();
            return (s.startsWith("http://") || s.startsWith("https://")) ? s : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripTags(String s) {
        return unescape(s.replaceAll("(?s)<[^>]*>", " ")).replaceAll("\\s+", " ").trim();
    }

    private static String clean(String title) {
        if (title == null) return null;
        String t = unescape(title).replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) return null;
        return t.length() > 200 ? t.substring(0, 200) : t;
    }

    static String unescape(String s) {
        if (s == null) return null;
        String r = s.replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&pound;", "£").replace("&euro;", "€");
        Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(r);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int cp;
            try {
                cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
            } catch (NumberFormatException e) {
                cp = '?';
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))));
        }
        m.appendTail(sb);
        return sb.toString().replace("&amp;", "&");
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }
}
