package com.bookstore.order.service;

import com.bookstore.order.model.LinkPaymentOption;
import com.bookstore.order.model.ReceivingMethod;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Cách tính tiền đơn order theo link (chỉ cửa hàng biết, không hiện cho khách):
 *  - Giá order 1 cuốn = giá bìa (đã đổi ra VND) × price-percent / 100, LÀM TRÒN LÊN tới round-to (mặc định 1.000₫)
 *  - Chuyển khoản toàn bộ: miễn phí ship, trả hết ngay
 *  - COD: cọc trước deposit-percent% GIÁ BÌA (làm tròn lên), cộng phí ship nếu giao hàng,
 *         phần còn lại trả khi nhận sách
 *  - Giảm giá voucher làm tròn xuống, để mọi số tiền đều chẵn nghìn
 */
@Component("linkPricing")
public class LinkPricing {

    @Value("${app.link.price-percent:85}")
    private int pricePercent;

    @Value("${app.link.deposit-percent:50}")
    private int depositPercent;

    @Value("${app.link.cod-shipping-fee:30000}")
    private long codShippingFee;

    /** Làm tròn tới bội số này (1000 = chẵn nghìn). Đặt 1 để không làm tròn */
    @Value("${app.link.round-to:1000}")
    private long roundTo;

    public record Line(long coverPrice, int quantity) {}

    public record Totals(long coverTotal, long subtotal, long discount, long shippingFee,
                         long total, long deposit, long payNow, long payOnDelivery) {}

    public long unitPrice(long coverPrice) {
        return unitPrice(coverPrice, pricePercent, roundTo);
    }

    /** Tiền cọc cho 1 cuốn, chưa làm tròn (JavaScript cộng lại rồi mới làm tròn, khớp với máy chủ) */
    public double depositUnit(long coverPrice) {
        return coverPrice * depositPercent / 100.0;
    }

    public Totals compute(List<Line> lines, LinkPaymentOption option, ReceivingMethod receiving, int voucherPercent) {
        return compute(lines, option, receiving, voucherPercent, pricePercent, depositPercent, codShippingFee, roundTo);
    }

    // ---- Hàm tính thuần (không phụ thuộc Spring) ----

    public static long roundUp(double value, long step) {
        if (step <= 1) return (long) Math.ceil(value);
        return (long) Math.ceil(value / step - 1e-9) * step;
    }

    public static long roundDown(double value, long step) {
        if (step <= 1) return (long) Math.floor(value);
        return (long) Math.floor(value / step + 1e-9) * step;
    }

    public static long unitPrice(long coverPrice, int pricePercent, long roundTo) {
        return roundUp(coverPrice * pricePercent / 100.0, roundTo);
    }

    public static Totals compute(List<Line> lines, LinkPaymentOption option, ReceivingMethod receiving,
                                 int voucherPercent, int pricePercent, int depositPercent,
                                 long codShippingFee, long roundTo) {
        long coverTotal = 0, subtotal = 0;
        for (Line l : lines) {
            coverTotal += l.coverPrice() * l.quantity();
            subtotal += unitPrice(l.coverPrice(), pricePercent, roundTo) * l.quantity();
        }
        long discount = roundDown(subtotal * Math.max(0, voucherPercent) / 100.0, roundTo);
        long afterDiscount = subtotal - discount;

        if (option == LinkPaymentOption.COD_DEPOSIT) {
            long shipping = receiving == ReceivingMethod.PICKUP ? 0 : codShippingFee;
            long total = afterDiscount + shipping;
            long deposit = Math.min(roundUp(coverTotal * depositPercent / 100.0, roundTo), total);
            return new Totals(coverTotal, subtotal, discount, shipping, total, deposit, deposit, total - deposit);
        }
        // Chuyển khoản toàn bộ -> miễn phí ship
        return new Totals(coverTotal, subtotal, discount, 0, afterDiscount, 0, afterDiscount, 0);
    }

    public int getPricePercent() { return pricePercent; }
    public int getDepositPercent() { return depositPercent; }
    public long getCodShippingFee() { return codShippingFee; }
    public long getRoundTo() { return roundTo; }
}
