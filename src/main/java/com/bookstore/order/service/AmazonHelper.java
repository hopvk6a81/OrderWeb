package com.bookstore.order.service;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tiện ích cho link Amazon: lấy mã ASIN, ảnh bìa, tên sách từ chính đường link */
public final class AmazonHelper {

    private AmazonHelper() {}

    private static final Pattern ASIN = Pattern.compile(
            "/(?:dp|gp/product|gp/aw/d|exec/obidos/ASIN|o/ASIN|product)/([A-Z0-9]{10})(?:[/?#]|$)",
            Pattern.CASE_INSENSITIVE);

    public static boolean isAmazon(String host) {
        return host != null && host.toLowerCase(Locale.ROOT).matches("(.+\\.)?amazon\\.[a-z.]{2,8}");
    }

    /** Mã sản phẩm 10 ký tự trong link Amazon (sách giấy thường trùng ISBN-10) */
    public static String asinOf(String url) {
        if (url == null) return null;
        Matcher m = ASIN.matcher(url);
        return m.find() ? m.group(1).toUpperCase(Locale.ROOT) : null;
    }

    /** Ảnh bìa theo ASIN – địa chỉ ảnh công khai của Amazon, dùng được kể cả khi trang sản phẩm chặn đọc */
    public static String imageForAsin(String asin) {
        return asin == null ? null : "https://images-na.ssl-images-amazon.com/images/P/" + asin + ".01.LZZZZZZZ.jpg";
    }

    /** Tiền mặc định của từng trang Amazon */
    public static String currencyFor(String host) {
        if (host == null) return "USD";
        if (host.endsWith(".co.uk")) return "GBP";
        if (host.endsWith(".de") || host.endsWith(".fr") || host.endsWith(".it") || host.endsWith(".es")) return "EUR";
        if (host.endsWith(".co.jp")) return "JPY";
        if (host.endsWith(".ca")) return "CAD";
        if (host.endsWith(".com.au")) return "AUD";
        return "USD";
    }

    /**
     * Tên sách lấy từ đường link, vd
     * amazon.com/Accidentally-Joined-Wolf-Shifter-Group-ebook/dp/B0... -> "Accidentally Joined Wolf Shifter Group"
     */
    public static String titleFromUrl(String url) {
        try {
            String path = URI.create(url.trim()).getRawPath();
            if (path == null) return null;
            String[] parts = path.split("/");
            for (int i = 0; i < parts.length - 1; i++) {
                if (parts[i + 1].equalsIgnoreCase("dp") || parts[i + 1].equalsIgnoreCase("gp")) {
                    String slug = URLDecoder.decode(parts[i], StandardCharsets.UTF_8);
                    if (slug.isBlank() || slug.length() < 3) return null;
                    String t = slug.replaceAll("[-_]+", " ")
                            .replaceAll("(?i)\\b(ebook|kindle edition|paperback|hardcover|audiobook)\\b", "")
                            .replaceAll("\\s+", " ").trim();
                    return t.isEmpty() ? null : t;
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    /**
     * Trang chặn bot / captcha / trang chờ kiểm tra – KHÔNG phải trang sách:
     * Amazon "Robot Check", Cloudflare "Just a moment...", PerimeterX, DataDome, Akamai, Imperva...
     */
    public static boolean looksBlocked(String html) {
        if (html == null || html.length() < 500) return true;
        String head = html.length() > 30000 ? html.substring(0, 30000) : html;
        String t = head.toLowerCase(Locale.ROOT);
        return t.contains("/errors/validatecaptcha") || t.contains("robot check")
                || t.contains("enter the characters you see below")
                || t.contains("to discuss automated access to amazon data")
                || t.contains("api-services-support@amazon.com")
                // Cloudflare
                || t.contains("<title>just a moment") || t.contains("challenges.cloudflare.com")
                || t.contains("cf-browser-verification") || t.contains("cf-challenge") || t.contains("cf_chl_")
                || t.contains("attention required! | cloudflare") || t.contains("checking your browser before accessing")
                || t.contains("enable javascript and cookies to continue")
                // PerimeterX / HUMAN, DataDome, Imperva, Akamai...
                || t.contains("px-captcha") || t.contains("press &amp; hold") || t.contains("press & hold")
                || t.contains("captcha-delivery.com") || t.contains("datadome")
                || t.contains("_incapsula_resource") || t.contains("request unsuccessful. incapsula")
                || (t.contains("access denied") && t.contains("reference #"))
                || t.contains("access to this page has been denied")
                || t.contains("are you a robot") || t.contains("verify you are human")
                || t.contains("pardon our interruption");
    }

    /** Tiêu đề kiểu trang chờ / trang lỗi – không dùng làm tên sách */
    public static boolean isJunkTitle(String title) {
        if (title == null) return true;
        String t = title.toLowerCase(Locale.ROOT).trim();
        return t.isEmpty() || t.startsWith("just a moment") || t.startsWith("attention required")
                || t.contains("access denied") || t.contains("robot check") || t.equals("amazon.com")
                || t.startsWith("pardon our interruption") || t.contains("are you a robot")
                || t.startsWith("403") || t.startsWith("404") || t.contains("page not found")
                || t.startsWith("loading") || t.equals("error");
    }
}
