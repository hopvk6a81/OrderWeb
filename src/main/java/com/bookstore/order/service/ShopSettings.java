package com.bookstore.order.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;

/** Thông tin cửa hàng đọc từ application.properties */
@Component("shop")
public class ShopSettings {

    @Value("${app.shop.name}")
    private String name;

    @Value("${app.shop.main-site-url}")
    private String mainSiteUrl;

    @Value("${app.shop.hotline}")
    private String hotline;

    @Value("${app.shop.shipping-fee}")
    private long shippingFee;

    @Value("${app.shop.free-shipping-from}")
    private long freeShippingFrom;

    @Value("${app.shop.bank-info}")
    private String bankInfo;

    private final Map<String, Integer> vouchers = new LinkedHashMap<>();

    /** Định dạng: MA1:10,MA2:15  (mã:phần trăm giảm) */
    @Value("${app.shop.vouchers:}")
    public void setVouchers(String raw) {
        vouchers.clear();
        if (raw == null) return;
        for (String part : raw.split(",")) {
            String[] kv = part.trim().split(":");
            if (kv.length == 2) {
                try {
                    int pct = Integer.parseInt(kv[1].trim());
                    if (pct > 0 && pct <= 100) vouchers.put(kv[0].trim().toUpperCase(), pct);
                } catch (NumberFormatException ignored) { }
            }
        }
    }

    public OptionalInt voucherPercent(String code) {
        if (code == null || code.isBlank()) return OptionalInt.empty();
        Integer pct = vouchers.get(code.trim().toUpperCase());
        return pct == null ? OptionalInt.empty() : OptionalInt.of(pct);
    }

    public long discountFor(long subtotal, String code) {
        return subtotal * voucherPercent(code).orElse(0) / 100;
    }

    public long shippingFeeFor(long amount) {
        if (amount <= 0) return 0;
        return amount >= freeShippingFrom ? 0 : shippingFee;
    }

    public String getName() { return name; }
    public String getMainSiteUrl() { return mainSiteUrl; }
    public String getHotline() { return hotline; }
    public long getShippingFee() { return shippingFee; }
    public long getFreeShippingFrom() { return freeShippingFrom; }
    public String getBankInfo() { return bankInfo; }
}
