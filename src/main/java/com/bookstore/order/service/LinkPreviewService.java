package com.bookstore.order.service;

import com.bookstore.order.model.CoverType;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Đọc thông tin sách từ link:
 *   1. Tải trang như trình duyệt (nhanh)
 *   2. Nếu chưa lấy được giá và là trang lớn (Amazon, Shopee...) -> mở bằng Chrome chạy ngầm
 *   3. Amazon: nếu vẫn thiếu ảnh / tên thì lấy theo mã sản phẩm trong link
 * Sau đó đổi giá bìa ra VND. Lưu tạm 30 phút để không tải lại một link nhiều lần.
 */
@Service
public class LinkPreviewService {

    private static final long TTL_MS = 30 * 60 * 1000L;
    private static final long FAIL_TTL_MS = 2 * 60 * 1000L;
    private static final int MAX_CACHE = 1000;

    private final LinkFetcher fetcher = new LinkFetcher();
    private final BrowserFetcher browser;
    private final CurrencyRates rates;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(LinkPreview preview, long expiresAt) {}

    public LinkPreviewService(BrowserFetcher browser, CurrencyRates rates) {
        this.browser = browser;
        this.rates = rates;
    }

    public LinkPreview preview(String url) {
        if (url == null || url.isBlank()) return LinkPreview.empty(null);
        String key = url.trim();
        long now = System.currentTimeMillis();
        Cached c = cache.get(key);
        LinkPreview raw;
        if (c != null && c.expiresAt() > now) {
            raw = c.preview();
        } else {
            raw = load(key);
            if (cache.size() > MAX_CACHE) cache.clear();
            cache.put(key, new Cached(raw, now + (raw.coverAmount() != null ? TTL_MS : FAIL_TTL_MS)));
        }
        // Quy đổi mỗi lần lấy ra, để đổi tỷ giá trong cấu hình là có hiệu lực ngay
        return raw.withVnd(rates.toVnd(raw.coverAmount(), raw.currency()));
    }

    /** Trạng thái giá của một loại bìa */
    public enum CoverStatus {
        /** Đọc được giá */
        OK,
        /** Trang có ô định dạng đầy đủ nhưng không có loại bìa này -> sách không bán loại bìa này */
        UNAVAILABLE,
        /** Không xác định được -> cửa hàng báo giá sau */
        UNKNOWN
    }

    /** Giá bìa cho đúng loại bìa khách chọn */
    public record PriceChoice(CoverStatus status, BigDecimal amount, String currency, Long vnd) {
        public boolean ok() { return status == CoverStatus.OK && vnd != null && vnd > 0; }
        public boolean unavailable() { return status == CoverStatus.UNAVAILABLE; }
        static PriceChoice of(CoverStatus s) { return new PriceChoice(s, null, null, null); }
    }

    /**
     * Chọn giá theo loại bìa:
     *  1. Trang đang mở đúng loại bìa khách chọn -> giá bìa của trang
     *  2. Ô chọn định dạng (Amazon, Barnes & Noble...) có loại bìa đó -> giá của loại bìa đó
     *     (bìa mềm trên trang Kindle: lấy giá cao hơn giữa "Print List Price" và ô định dạng)
     *  3. Ô chọn định dạng đầy đủ mà không có loại bìa đó -> KHÔNG BÁN (khách không chọn được)
     *  4. Trang Việt Nam không ghi loại bìa (đa số là bìa mềm) -> giá trang áp cho bìa mềm
     *  5. Còn lại -> chưa rõ, chờ cửa hàng báo giá
     */
    public PriceChoice priceFor(LinkPreview p, CoverType cover) {
        if (p == null || cover == null) return PriceChoice.of(CoverStatus.UNKNOWN);
        String code = cover.name();
        String pageFmt = p.format();
        boolean pagePrint = "PAPERBACK".equals(pageFmt) || "HARDCOVER".equals(pageFmt) || "LARGE_PRINT".equals(pageFmt);
        BigDecimal swatch = p.formatPrices().get(code);
        String currency = p.currency() != null ? p.currency()
                : (AmazonHelper.isAmazon(p.host()) ? AmazonHelper.currencyFor(p.host()) : null);

        BigDecimal amount = null;
        if (p.coverAmount() != null && code.equals(pageFmt)) {
            amount = p.coverAmount();
        } else if (swatch != null) {
            amount = (!pagePrint && cover == CoverType.PAPERBACK && p.coverAmount() != null)
                    ? p.coverAmount().max(swatch) : swatch;
        } else if (p.formatsListed() && !code.equals(pageFmt)) {
            return PriceChoice.of(CoverStatus.UNAVAILABLE);
        } else if (pageFmt == null && cover == CoverType.PAPERBACK && p.coverAmount() != null
                && p.host() != null && p.host().endsWith(".vn")) {
            amount = p.coverAmount();
        }
        if (amount == null) return PriceChoice.of(CoverStatus.UNKNOWN);
        Long vnd = rates.toVnd(amount, currency);
        return new PriceChoice(vnd == null ? CoverStatus.UNKNOWN : CoverStatus.OK, amount, currency, vnd);
    }

    /** Loại bìa gợi ý: loại của trang đang mở nếu có giá; không thì loại đầu tiên có giá; không rõ thì bìa mềm */
    public CoverType suggestedCover(LinkPreview p) {
        if (p == null) return CoverType.PAPERBACK;
        for (CoverType c : CoverType.values()) {
            if (c.name().equals(p.format()) && priceFor(p, c).ok()) return c;
        }
        for (CoverType c : CoverType.values()) {
            if (priceFor(p, c).ok()) return c;
        }
        for (CoverType c : CoverType.values()) {
            if (!priceFor(p, c).unavailable()) return c;
        }
        return CoverType.PAPERBACK;
    }

    private LinkPreview load(String url) {
        String host = LinkPreviewParser.hostOf(url);

        String html = fetcher.fetchHtml(url).orElse(null);
        LinkPreview p = html != null ? LinkPreviewParser.parse(html, url) : LinkPreview.empty(host);

        // Cửa hàng Shopify (vd nyrb.com): đọc dữ liệu sách từ /products/ten-sach.js (giá từng loại bìa, ISBN)
        if (p.coverAmount() == null && (html == null || StoreVariants.isShopify(html))) {
            LinkPreview viaJson = shopifyJson(url, html, host);
            if (viaJson != null) p = merge(viaJson, p);
        }

        // Chưa có giá (bị chặn / trang hiện giá bằng JavaScript) -> mở bằng Chrome
        if (p.coverAmount() == null && browser.canHandle(host)) {
            LinkPreview viaBrowser = browser.fetch(url)
                    .map(page -> LinkPreviewParser.parse(page, url))
                    .orElse(null);
            if (viaBrowser != null && (viaBrowser.coverAmount() != null || p.title() == null)) {
                p = merge(viaBrowser, p);
            }
        }

        // Trang chặn máy đọc (vd faber.co.uk) nhưng biết ISBN -> lấy giá bìa (RRP) của đúng ISBN đó trên Blackwell's.
        // Giá bìa do nhà xuất bản đặt nên giống nhau ở mọi hiệu sách.
        if (p.coverAmount() == null) {
            String isbn = p.isbn() != null ? p.isbn() : Isbn.normalize(UrlInfo.isbnOf(url));
            if (isbn != null && !host.endsWith("blackwells.co.uk")) {
                LinkPreview byIsbn = byIsbn(isbn);
                if (byIsbn != null && byIsbn.coverAmount() != null) p = merge(p, byIsbn);
            }
        }

        String title = p.title();
        String image = p.imageUrl();
        String format = p.format();
        if (AmazonHelper.isAmazon(host)) {
            String asin = AmazonHelper.asinOf(url);
            if (title == null) title = AmazonHelper.titleFromUrl(url);
            if (image == null) image = AmazonHelper.imageForAsin(asin);
            if (format == null) format = formatFromUrl(url);
        }
        // Mọi trang: thiếu tên -> đoán từ đường link; thiếu ảnh -> ảnh bìa theo ISBN trong link (Open Library)
        if (title == null) title = UrlInfo.titleFromUrl(url);
        if (format == null) format = formatFromUrl(url);
        String isbn = p.isbn() != null ? p.isbn() : Isbn.normalize(UrlInfo.isbnOf(url));
        if (image == null) image = UrlInfo.coverImageForIsbn(isbn);
        p = new LinkPreview(title, image, p.coverAmount(), p.currency(), p.saleAmount(), host, null,
                format, p.formatPrices(), p.formatsListed(), isbn);
        return p;
    }

    /** Đọc trang sách theo ISBN trên Blackwell's (thử tải thường, không được thì mở bằng Chrome) */
    private LinkPreview byIsbn(String isbn) {
        String bwUrl = "https://blackwells.co.uk/bookshop/product/" + isbn;
        String bwHost = "blackwells.co.uk";
        LinkPreview bw = fetcher.fetchHtml(bwUrl).map(page -> LinkPreviewParser.parse(page, bwUrl)).orElse(null);
        if ((bw == null || bw.coverAmount() == null) && browser.canHandle(bwHost)) {
            bw = browser.fetch(bwUrl).map(page -> LinkPreviewParser.parse(page, bwUrl)).orElse(bw);
        }
        if (bw == null) return null;
        // Chỉ dùng khi trang Blackwell's đúng là cuốn có ISBN này
        if (bw.isbn() != null && !bw.isbn().equals(isbn)) return null;
        return bw;
    }

    private static final java.util.regex.Pattern SHOPIFY_PRODUCT = java.util.regex.Pattern.compile(
            "^(https?://[^/?#]+)(?:/[a-zA-Z]{2}(?:-[a-zA-Z]{2})?)?/(?:collections/[^/?#]+/)?products/([^/?#.]+)");

    private LinkPreview shopifyJson(String url, String html, String host) {
        java.util.regex.Matcher m = SHOPIFY_PRODUCT.matcher(url.trim());
        if (!m.find()) return null;
        String json = fetcher.fetchJson(m.group(1) + "/products/" + m.group(2) + ".js").orElse(null);
        if (json == null) return null;
        String currency = html == null ? null : StoreVariants.shopifyCurrency(html);
        if (currency == null) {
            currency = LinkPreviewParser.pageCurrency(html == null ? "" : html,
                    html == null ? java.util.List.of() : LinkPreviewParser.readMetaTags(html), host);
        }
        return LinkPreviewParser.fromShopifyJson(json, url, host, currency);
    }

    /** Ưu tiên thông tin của a, thiếu thì lấy của b */
    private static LinkPreview merge(LinkPreview a, LinkPreview b) {
        return new LinkPreview(
                a.title() != null ? a.title() : b.title(),
                a.imageUrl() != null ? a.imageUrl() : b.imageUrl(),
                a.coverAmount() != null ? a.coverAmount() : b.coverAmount(),
                a.coverAmount() != null ? a.currency() : b.currency(),
                a.saleAmount() != null ? a.saleAmount() : b.saleAmount(),
                a.host() != null ? a.host() : b.host(), null,
                a.format() != null ? a.format() : b.format(),
                !a.formatPrices().isEmpty() ? a.formatPrices() : b.formatPrices(),
                a.formatsListed() || b.formatsListed(),
                a.isbn() != null ? a.isbn() : b.isbn());
    }

    /** Link Amazon thường có chữ "ebook" / "paperback" / "hardcover" trong đường dẫn */
    private static String formatFromUrl(String url) {
        String path = url.toLowerCase(java.util.Locale.ROOT);
        if (path.contains("-ebook/") || path.contains("/ebook/")) return "EBOOK";
        if (path.contains("large-print") || path.contains("large_print")) return "LARGE_PRINT";
        if (path.contains("hardcover")) return "HARDCOVER";
        if (path.contains("paperback")) return "PAPERBACK";
        return null;
    }
}
