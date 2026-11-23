package com.bookstore.order.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tỷ giá quy đổi ra VND, cấu hình trong application.properties:
 *   app.currency.rates=USD:26000,GBP:35000
 * VND luôn = 1.
 */
@Component("rates")
public class CurrencyRates {

    private final Map<String, Long> rates = new LinkedHashMap<>();

    @Value("${app.currency.rates:USD:26000,GBP:35000}")
    public void setRates(String raw) {
        rates.clear();
        rates.put("VND", 1L);
        for (String part : raw.split(",")) {
            String[] kv = part.trim().split(":");
            if (kv.length == 2) {
                try {
                    rates.put(kv[0].trim().toUpperCase(), Long.parseLong(kv[1].trim().replaceAll("[^0-9]", "")));
                } catch (NumberFormatException ignored) { }
            }
        }
    }

    public boolean supports(String currency) {
        return currency != null && rates.containsKey(currency.toUpperCase());
    }

    /** Đổi ra VND (làm tròn đến đồng), null nếu loại tiền không hỗ trợ */
    public Long toVnd(BigDecimal amount, String currency) {
        if (amount == null || !supports(currency)) return null;
        return amount.multiply(BigDecimal.valueOf(rates.get(currency.toUpperCase())))
                .setScale(0, RoundingMode.HALF_UP).longValue();
    }

    /** Các loại tiền khách được chọn khi tự nhập giá: VND, USD, GBP... */
    public Map<String, Long> getRates() {
        return rates;
    }

    public long rateOf(String currency) {
        return rates.getOrDefault(currency == null ? "" : currency.toUpperCase(), 0L);
    }

    /** Hiển thị giá gốc: $12.99, £9.99, 120.000₫ */
    public static String format(BigDecimal amount, String currency) {
        if (amount == null || currency == null) return "";
        return switch (currency.toUpperCase()) {
            case "USD" -> "$" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
            case "GBP" -> "£" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
            case "EUR" -> "€" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
            case "VND" -> String.format("%,d", amount.setScale(0, RoundingMode.HALF_UP).longValue()).replace(',', '.') + "₫";
            default -> amount.toPlainString() + " " + currency;
        };
    }
}
