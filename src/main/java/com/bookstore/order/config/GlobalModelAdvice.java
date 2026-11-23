package com.bookstore.order.config;

import com.bookstore.order.service.CartService;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Đưa số lượng sách trong giỏ vào mọi trang (hiện trên thanh menu) */
@ControllerAdvice
public class GlobalModelAdvice {

    private final CartService cart;

    public GlobalModelAdvice(CartService cart) {
        this.cart = cart;
    }

    @ModelAttribute("cartCount")
    public int cartCount() {
        return cart.getCount();
    }
}
