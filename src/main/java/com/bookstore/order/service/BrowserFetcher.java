package com.bookstore.order.service;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Mở trang sách bằng Chrome thật (đã cài trên máy) khi cách đọc thường bị chặn
 * hoặc trang hiện giá bằng JavaScript (ThriftBooks, Books-A-Million, Shopee...).
 *
 *  1. Thử Chrome chạy ngầm (không cửa sổ)
 *  2. Nếu vẫn gặp trang chống bot (Cloudflare "Just a moment..."), thử lại bằng Chrome có cửa sổ
 *     nhưng đặt ngoài màn hình – trang chống bot ít chặn kiểu này hơn
 *  Phiên trình duyệt (cookie) được giữ lại, lần sau vào cùng trang sẽ nhanh và ít bị chặn hơn.
 *
 * An toàn: mọi yêu cầu của trang tới địa chỉ nội bộ (localhost, 192.168.x, 10.x...) đều bị chặn.
 * Nếu máy không có Chrome / lỗi khởi động thì tự tắt, phần còn lại vẫn chạy bình thường.
 */
@Component
public class BrowserFetcher implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(BrowserFetcher.class);

    @Value("${app.link.browser.enabled:true}")
    private boolean enabled;

    /** chrome = Google Chrome đã cài; msedge = Microsoft Edge; để trống = Chromium của Playwright (tự tải) */
    @Value("${app.link.browser.channel:chrome}")
    private String channel;

    /** true = dùng cho mọi trang; false = chỉ các tên miền trong app.link.browser.domains */
    @Value("${app.link.browser.all-domains:true}")
    private boolean allDomains;

    /** Thử lại bằng Chrome có cửa sổ (đặt ngoài màn hình) khi bị trang chống bot chặn */
    @Value("${app.link.browser.retry-with-window:true}")
    private boolean retryWithWindow;

    private Set<String> domains = Set.of();

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "link-browser");
        t.setDaemon(true);
        return t;
    });

    private Playwright playwright;
    private final Map<Boolean, Browser> browsers = new HashMap<>();          // key: headless?
    private final Map<Boolean, BrowserContext> contexts = new HashMap<>();
    private final Map<String, Boolean> hostCheck = new HashMap<>();          // host -> địa chỉ công khai?
    private volatile boolean broken;

    @Value("${app.link.browser.domains:amazon.com,amazon.co.uk,shopee.vn,lazada.vn,tiki.vn,fahasa.com,barnesandnoble.com,thriftbooks.com,betterworldbooks.com,booksamillion.com}")
    public void setDomains(String raw) {
        domains = Arrays.stream(raw.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    public boolean canHandle(String host) {
        if (!enabled || broken || host == null) return false;
        if (allDomains) return true;
        String h = host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        for (String d : domains) {
            if (h.equals(d) || h.endsWith("." + d)) return true;
        }
        return false;
    }

    public Optional<String> fetch(String url) {
        URI uri;
        try {
            uri = URI.create(url.trim());
            LinkFetcher.checkAllowed(uri);
        } catch (Exception e) {
            return Optional.empty();
        }
        if (!canHandle(uri.getHost())) return Optional.empty();

        Future<String> task = worker.submit(() -> {
            String html = render(uri, true);
            if (html == null && retryWithWindow && !broken) {
                log.info("Trang {} chặn Chrome chạy ngầm, thử lại bằng Chrome có cửa sổ (ngoài màn hình)", uri.getHost());
                html = render(uri, false);
            }
            return html;
        });
        try {
            return Optional.ofNullable(task.get(75, TimeUnit.SECONDS));
        } catch (Exception e) {
            task.cancel(true);
            log.warn("Không đọc được {} bằng Chrome: {}", uri.getHost(), e.toString());
            return Optional.empty();
        }
    }

    /** Mở trang, chờ tải xong và qua bước kiểm tra chống bot (nếu có); trả null nếu vẫn bị chặn */
    private String render(URI uri, boolean headless) {
        BrowserContext ctx = context(headless);
        if (ctx == null) return null;
        Page page = null;
        try {
            page = ctx.newPage();
            page.navigate(uri.toString(), new Page.NavigateOptions()
                    .setTimeout(30_000)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            waitQuietly(page, 8_000);

            // Trang chờ kiểm tra (Cloudflare...) thường tự chuyển sau vài giây trong trình duyệt thật
            for (int i = 0; i < 6 && AmazonHelper.looksBlocked(page.content()); i++) {
                page.waitForTimeout(2_500);
                waitQuietly(page, 4_000);
            }
            page.waitForTimeout(1_500); // chờ giá hiện ra (trang dùng JavaScript)
            String html = page.content();
            return AmazonHelper.looksBlocked(html) ? null : html;
        } catch (Exception e) {
            log.warn("Chrome lỗi khi mở {}: {}", uri.getHost(), e.getMessage());
            resetContext(headless);
            return null;
        } finally {
            if (page != null) {
                try { page.close(); } catch (Exception ignored) { }
            }
        }
    }

    private static void waitQuietly(Page page, int ms) {
        try {
            page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(ms));
        } catch (Exception ignored) {
            // trang có quảng cáo / theo dõi chạy liên tục -> không bao giờ "yên", bỏ qua
        }
    }

    /** Phiên trình duyệt dùng lại giữa các lần đọc (giữ cookie) */
    private BrowserContext context(boolean headless) {
        BrowserContext ctx = contexts.get(headless);
        if (ctx != null) return ctx;
        Browser browser = browser(headless);
        if (browser == null) return null;
        try {
            ctx = browser.newContext(new Browser.NewContextOptions()
                    .setUserAgent(LinkFetcher.USER_AGENT)
                    .setLocale("en-US")
                    .setViewportSize(1366, 900));
            ctx.addInitScript("Object.defineProperty(navigator, 'webdriver', { get: () => undefined });");

            // Ép Amazon hiện giá bằng tiền gốc (USD/GBP) thay vì tự đổi sang tiền Việt
            List<Cookie> cookies = new ArrayList<>();
            for (String[] d : new String[][]{{".amazon.com", "USD", "en_US"}, {".amazon.co.uk", "GBP", "en_GB"},
                    {".amazon.ca", "CAD", "en_CA"}, {".amazon.com.au", "AUD", "en_AU"}}) {
                cookies.add(new Cookie("i18n-prefs", d[1]).setDomain(d[0]).setPath("/"));
                cookies.add(new Cookie("lc-main", d[2]).setDomain(d[0]).setPath("/"));
            }
            ctx.addCookies(cookies);

            // Bỏ ảnh / video / font cho nhanh; chặn MỌI yêu cầu tới địa chỉ nội bộ
            ctx.route("**/*", route -> {
                String type = route.request().resourceType();
                if (type.equals("image") || type.equals("media") || type.equals("font")) {
                    route.abort();
                    return;
                }
                if (!isPublicUrl(route.request().url())) {
                    route.abort();
                    return;
                }
                route.resume();
            });
            contexts.put(headless, ctx);
            return ctx;
        } catch (Exception e) {
            log.warn("Không tạo được phiên Chrome: {}", e.getMessage());
            return null;
        }
    }

    private boolean isPublicUrl(String url) {
        try {
            URI u = URI.create(url);
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if (scheme.equals("data") || scheme.equals("blob") || scheme.equals("about")) return true;
            if (!scheme.equals("http") && !scheme.equals("https")) return false;
            String host = u.getHost();
            if (host == null) return false;
            Boolean ok = hostCheck.get(host);
            if (ok == null) {
                ok = true;
                for (InetAddress a : InetAddress.getAllByName(host)) {
                    if (!LinkFetcher.isPublic(a)) { ok = false; break; }
                }
                if (hostCheck.size() > 5000) hostCheck.clear();
                hostCheck.put(host, ok);
            }
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    private Browser browser(boolean headless) {
        Browser b = browsers.get(headless);
        if (b != null && b.isConnected()) return b;
        try {
            if (playwright == null) {
                Playwright.CreateOptions co = new Playwright.CreateOptions();
                // Dùng Chrome/Edge đã cài sẵn -> không cần tải trình duyệt riêng
                if (channel != null && !channel.isBlank()) co.setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1"));
                playwright = Playwright.create(co);
            }
            List<String> args = new ArrayList<>(List.of("--disable-blink-features=AutomationControlled"));
            if (!headless) {
                // Cửa sổ thật nhưng đặt ngoài màn hình, người dùng không thấy
                args.add("--window-position=-32000,-32000");
                args.add("--window-size=1366,900");
            }
            BrowserType.LaunchOptions lo = new BrowserType.LaunchOptions().setHeadless(headless).setArgs(args);
            if (channel != null && !channel.isBlank()) lo.setChannel(channel.trim());
            b = playwright.chromium().launch(lo);
            browsers.put(headless, b);
            log.info("Đã khởi động Chrome {} để đọc giá sách", headless ? "chạy ngầm" : "có cửa sổ (ngoài màn hình)");
            return b;
        } catch (Exception e) {
            if (headless) {
                broken = true;
                log.warn("Không khởi động được Chrome ({}). Tính năng đọc giá bằng Chrome sẽ tắt. Lỗi: {}",
                        channel, e.getMessage());
            } else {
                retryWithWindow = false;
                log.warn("Không mở được Chrome có cửa sổ, chỉ dùng chế độ chạy ngầm. Lỗi: {}", e.getMessage());
            }
            return null;
        }
    }

    private void resetContext(boolean headless) {
        BrowserContext ctx = contexts.remove(headless);
        try { if (ctx != null) ctx.close(); } catch (Exception ignored) { }
        Browser b = browsers.get(headless);
        if (b != null && !b.isConnected()) browsers.remove(headless);
    }

    @Override
    public void destroy() {
        try {
            worker.submit(() -> {
                for (BrowserContext c : contexts.values()) {
                    try { c.close(); } catch (Exception ignored) { }
                }
                for (Browser b : browsers.values()) {
                    try { b.close(); } catch (Exception ignored) { }
                }
                try { if (playwright != null) playwright.close(); } catch (Exception ignored) { }
            }).get(10, TimeUnit.SECONDS);
        } catch (Exception ignored) { }
        worker.shutdownNow();
    }
}
