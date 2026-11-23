package com.bookstore.order.controller;

import com.bookstore.order.model.CoverType;
import com.bookstore.order.service.LinkPreview;
import com.bookstore.order.service.LinkPreviewService;
import com.bookstore.order.service.LinkPricing;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trang Order by link gọi API này (bằng JavaScript) khi khách dán link,
 * để hiện ảnh, tên sách, giá bìa và giá bán ngay trong ô link.
 */
@RestController
public class LinkPreviewApiController {

    /** Mỗi địa chỉ IP tối đa 40 lần đọc link trong 10 phút (chống lạm dụng) */
    private static final int LIMIT = 40;
    private static final long WINDOW_MS = 10 * 60 * 1000L;

    private final LinkPreviewService previewService;
    private final LinkPricing pricing;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public LinkPreviewApiController(LinkPreviewService previewService, LinkPricing pricing) {
        this.previewService = previewService;
        this.pricing = pricing;
    }

    @GetMapping("/api/link-preview")
    public ResponseEntity<Map<String, Object>> preview(@RequestParam String url, HttpServletRequest request) {
        Map<String, Object> res = new LinkedHashMap<>();
        if (!url.matches("^https?://\\S+$") || url.length() > 2000) {
            res.put("ok", false);
            res.put("message", "Please paste a valid link");
            return ResponseEntity.ok(res);
        }
        if (tooMany(request.getRemoteAddr())) {
            res.put("ok", false);
            res.put("message", "Too many requests, please wait a moment");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(res);
        }

        LinkPreview p = previewService.preview(url);
        // Mỗi loại bìa: giá bìa (đã đổi ra VND) và giá bán cho khách (máy chủ tính sẵn, đã làm tròn).
        // Không gửi tỷ lệ giảm ra trình duyệt.
        Map<String, Object> covers = new LinkedHashMap<>();
        boolean anyPrice = false;
        for (CoverType c : CoverType.values()) {
            LinkPreviewService.PriceChoice choice = previewService.priceFor(p, c);
            Map<String, Object> m = new LinkedHashMap<>();
            if (choice.ok()) {
                m.put("status", "ok");
                m.put("coverPrice", choice.vnd());
                m.put("unitPrice", pricing.unitPrice(choice.vnd()));
                m.put("depositUnit", pricing.depositUnit(choice.vnd()));
                anyPrice = true;
            } else {
                m.put("status", choice.unavailable() ? "unavailable" : "unknown");
            }
            covers.put(c.name(), m);
        }
        res.put("ok", anyPrice || p.title() != null || p.imageUrl() != null);
        res.put("host", p.host());
        res.put("title", p.title());
        res.put("image", p.imageUrl());
        res.put("isbn", p.isbn());
        res.put("pageFormat", p.format());
        res.put("suggestedCover", previewService.suggestedCover(p).name());
        res.put("covers", covers);
        return ResponseEntity.ok(res);
    }

    private boolean tooMany(String ip) {
        long now = System.currentTimeMillis();
        if (hits.size() > 10_000) hits.clear();
        Deque<Long> q = hits.computeIfAbsent(ip == null ? "?" : ip, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > WINDOW_MS) q.pollFirst();
            if (q.size() >= LIMIT) return true;
            q.addLast(now);
            return false;
        }
    }
}
