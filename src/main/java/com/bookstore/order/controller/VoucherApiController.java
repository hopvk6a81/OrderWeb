package com.bookstore.order.controller;

import com.bookstore.order.service.ShopSettings;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;

/** Nút "Apply" voucher gọi API này bằng JavaScript để báo mã đúng/sai ngay trên trang */
@RestController
public class VoucherApiController {

    private final ShopSettings shop;

    public VoucherApiController(ShopSettings shop) {
        this.shop = shop;
    }

    @GetMapping("/api/voucher")
    public Map<String, Object> check(@RequestParam String code,
                                     @RequestParam(defaultValue = "0") long subtotal) {
        OptionalInt pct = shop.voucherPercent(code);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("valid", pct.isPresent());
        if (pct.isPresent()) {
            res.put("code", code.trim().toUpperCase());
            res.put("percent", pct.getAsInt());
            res.put("discount", subtotal * pct.getAsInt() / 100);
            res.put("message", pct.getAsInt() + "% off applied");
        } else {
            res.put("message", "This voucher code is not valid");
        }
        return res;
    }
}
