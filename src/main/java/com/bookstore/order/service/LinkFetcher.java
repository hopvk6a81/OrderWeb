package com.bookstore.order.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Tải mã HTML của link sách khách gửi, gửi kèm thông tin giống trình duyệt Chrome thật
 * để các trang lớn (Amazon...) không chặn. Có các lớp bảo vệ vì link do người ngoài nhập:
 *  - chỉ cho http/https, cổng 80/443
 *  - chặn địa chỉ nội bộ (localhost, 192.168.x, 10.x...), kiểm tra lại sau mỗi lần chuyển hướng
 *  - giới hạn thời gian và dung lượng tải
 * Trả về rỗng nếu trang là captcha / "Robot Check".
 */
public class LinkFetcher {

    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;
    static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(6))
            .build();

    /** @return HTML của trang, hoặc rỗng nếu không tải được / bị chặn */
    public Optional<String> fetchHtml(String url) {
        return fetch(url, false);
    }

    /** Tải dữ liệu JSON (vd /products/ten-sach.js của cửa hàng Shopify), cùng các lớp bảo vệ như trang HTML */
    public Optional<String> fetchJson(String url) {
        return fetch(url, true);
    }

    private Optional<String> fetch(String url, boolean json) {
        try {
            URI uri = URI.create(url.trim());
            String cookies = "";
            for (int i = 0; i <= MAX_REDIRECTS; i++) {
                checkAllowed(uri);
                HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(10))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", json ? "application/json, text/javascript, */*;q=0.1"
                                : "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                        .header("Accept-Language", acceptLanguage(uri.getHost()))
                        .header("Accept-Encoding", "gzip, deflate")
                        .header("Upgrade-Insecure-Requests", "1")
                        .header("Sec-Ch-Ua", "\"Chromium\";v=\"130\", \"Google Chrome\";v=\"130\", \"Not?A_Brand\";v=\"99\"")
                        .header("Sec-Ch-Ua-Mobile", "?0")
                        .header("Sec-Ch-Ua-Platform", "\"Windows\"")
                        .header("Sec-Fetch-Dest", "document")
                        .header("Sec-Fetch-Mode", "navigate")
                        .header("Sec-Fetch-Site", "none")
                        .header("Sec-Fetch-User", "?1");
                String cookie = joinCookies(siteCookies(uri.getHost()), cookies);
                if (!cookie.isEmpty()) b.header("Cookie", cookie);

                HttpResponse<InputStream> res = client.send(b.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
                // giữ cookie trang trả về (Amazon cần cookie phiên khi chuyển hướng)
                for (String sc : res.headers().allValues("Set-Cookie")) {
                    String kv = sc.split(";", 2)[0].trim();
                    if (kv.contains("=")) cookies = joinCookies(cookies, kv);
                }
                int code = res.statusCode();
                if (code >= 300 && code < 400) {
                    res.body().close();
                    Optional<String> loc = res.headers().firstValue("Location");
                    if (loc.isEmpty()) return Optional.empty();
                    uri = uri.resolve(loc.get().trim());
                    continue;
                }
                if (code != 200) {
                    res.body().close();
                    return Optional.empty();
                }
                String type = res.headers().firstValue("Content-Type").orElse("text/html").toLowerCase(Locale.ROOT);
                boolean okType = json ? (type.contains("json") || type.contains("javascript"))
                        : (type.contains("html") || type.contains("xml"));
                if (!okType) {
                    res.body().close();
                    return Optional.empty();
                }
                String encoding = res.headers().firstValue("Content-Encoding").orElse("").toLowerCase(Locale.ROOT);
                byte[] body = readLimited(decode(res.body(), encoding));
                String html = new String(body, charsetOf(type, body));
                if (json) return Optional.of(html);
                return AmazonHelper.looksBlocked(html) ? Optional.empty() : Optional.of(html);
            }
        } catch (IllegalArgumentException | IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        return Optional.empty();
    }

    /** Ép Amazon hiện giá bằng tiền gốc của trang (USD với amazon.com) thay vì tự đổi sang VND */
    static String siteCookies(String host) {
        if (!AmazonHelper.isAmazon(host)) return "";
        String cur = AmazonHelper.currencyFor(host);
        String lc = host.endsWith(".co.uk") ? "en_GB" : "en_US";
        return "i18n-prefs=" + cur + "; lc-main=" + lc;
    }

    private static String acceptLanguage(String host) {
        if (host != null && host.endsWith(".vn")) return "vi-VN,vi;q=0.9,en;q=0.8";
        if (host != null && host.endsWith(".uk")) return "en-GB,en;q=0.9";
        return "en-US,en;q=0.9";
    }

    private static String joinCookies(String a, String b) {
        if (a == null || a.isBlank()) return b == null ? "" : b;
        if (b == null || b.isBlank()) return a;
        return a + "; " + b;
    }

    private static InputStream decode(InputStream in, String encoding) throws IOException {
        if (encoding.contains("gzip")) return new GZIPInputStream(in);
        if (encoding.contains("deflate")) return new InflaterInputStream(in);
        return in;
    }

    /** Ném IllegalArgumentException nếu link không được phép tải */
    static void checkAllowed(URI uri) throws UnknownHostException {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) throw new IllegalArgumentException("scheme");
        if (uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException("host");
        int port = uri.getPort();
        if (port != -1 && port != 80 && port != 443) throw new IllegalArgumentException("port");
        for (InetAddress addr : InetAddress.getAllByName(uri.getHost())) {
            if (!isPublic(addr)) throw new IllegalArgumentException("private address");
        }
    }

    static boolean isPublic(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) return false;
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int b0 = b[0] & 0xff, b1 = b[1] & 0xff;
            if (b0 == 0 || b0 >= 224) return false;                 // 0.x, multicast/reserved
            if (b0 == 100 && b1 >= 64 && b1 <= 127) return false;   // 100.64.0.0/10
            if (b0 == 169 && b1 == 254) return false;               // link-local / cloud metadata
            if (b0 == 198 && (b1 == 18 || b1 == 19)) return false;  // benchmark
        } else if (a instanceof Inet6Address) {
            int b0 = b[0] & 0xff;
            if ((b0 & 0xfe) == 0xfc) return false;                  // fc00::/7 unique-local
        }
        return true;
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        try (in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n, total = 0;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_BYTES) break;
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static Charset charsetOf(String contentType, byte[] body) {
        String cs = null;
        int i = contentType.indexOf("charset=");
        if (i >= 0) cs = contentType.substring(i + 8).replaceAll("[\"';\\s].*$", "");
        if (cs == null) {
            String head = new String(body, 0, Math.min(body.length, 4096), StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
            int j = head.indexOf("charset=");
            if (j >= 0) cs = head.substring(j + 8).replaceAll("^[\"']", "").replaceAll("[\"'\\s/>;].*$", "");
        }
        try {
            return cs == null || cs.isBlank() ? StandardCharsets.UTF_8 : Charset.forName(cs);
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }
}
