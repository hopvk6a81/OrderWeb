package com.bookstore.order.controller;

import com.bookstore.order.dto.CheckoutForm;
import com.bookstore.order.model.CustomerOrder;
import com.bookstore.order.model.ReceivingMethod;
import com.bookstore.order.repository.BookRepository;
import com.bookstore.order.repository.OrderRepository;
import com.bookstore.order.service.CartService;
import com.bookstore.order.service.OrderService;
import com.bookstore.order.service.ShopSettings;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Controller
public class CartController {

    private static final String LAST_ORDER = "LAST_ORDER_CODE";

    private final CartService cart;
    private final BookRepository bookRepository;
    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final ShopSettings shop;

    public CartController(CartService cart, BookRepository bookRepository, OrderRepository orderRepository,
                          OrderService orderService, ShopSettings shop) {
        this.cart = cart;
        this.bookRepository = bookRepository;
        this.orderRepository = orderRepository;
        this.orderService = orderService;
        this.shop = shop;
    }

    @PostMapping("/cart/add")
    public String add(@RequestParam Long bookId,
                      @RequestParam(defaultValue = "1") int quantity,
                      @RequestParam(defaultValue = "false") boolean buyNow,
                      @RequestHeader(value = "Referer", required = false) String referer,
                      RedirectAttributes ra) {
        var book = bookRepository.findById(bookId).orElse(null);
        if (book == null || !book.isActive()) {
            ra.addFlashAttribute("error", "Book not found");
            return "redirect:/";
        }
        if (!book.isInStock()) {
            ra.addFlashAttribute("error", "\"" + book.getTitle()
                    + "\" is out of stock. Send us a link and we'll order it for you.");
            return "redirect:/order?title=" + URLEncoder.encode(book.getTitle(), StandardCharsets.UTF_8);
        }
        cart.add(bookId, Math.max(1, quantity));
        if (buyNow) return "redirect:/checkout";

        ra.addFlashAttribute("success", "Added \"" + book.getTitle() + "\" to your cart");
        return "redirect:" + safeBack(referer);
    }

    @GetMapping("/cart")
    public String view(Model model) {
        addCartToModel(model, ReceivingMethod.DELIVERY, null);
        return "cart";
    }

    @PostMapping("/cart/update")
    public String update(@RequestParam Long bookId, @RequestParam int quantity) {
        cart.update(bookId, quantity);
        return "redirect:/cart";
    }

    @PostMapping("/cart/remove")
    public String remove(@RequestParam Long bookId) {
        cart.remove(bookId);
        return "redirect:/cart";
    }

    @GetMapping("/checkout")
    public String checkoutForm(Model model) {
        if (cart.isEmpty()) return "redirect:/cart";
        CheckoutForm form = new CheckoutForm();
        model.addAttribute("form", form);
        addCartToModel(model, form.getReceivingMethod(), null);
        return "checkout";
    }

    @PostMapping("/checkout")
    public String checkout(@Valid @ModelAttribute("form") CheckoutForm form, BindingResult result,
                           Model model, HttpSession session) {
        List<CartService.CartLine> lines = cart.getLines();
        if (lines.isEmpty()) return "redirect:/cart";

        FormChecks.validate(form, result, shop);
        if (result.hasErrors()) {
            addCartToModel(model, form.getReceivingMethod(), form.getVoucherCode());
            return "checkout";
        }
        try {
            CustomerOrder order = orderService.placeOrder(form, lines);
            cart.clear();
            session.setAttribute(LAST_ORDER, order.getCode());
            return "redirect:/checkout/success?code=" + order.getCode();
        } catch (IllegalStateException ex) {
            model.addAttribute("error", ex.getMessage());
            addCartToModel(model, form.getReceivingMethod(), form.getVoucherCode());
            return "checkout";
        }
    }

    @GetMapping("/checkout/success")
    public String success(@RequestParam String code, HttpSession session, Model model) {
        if (!code.equals(session.getAttribute(LAST_ORDER))) return "redirect:/track";
        CustomerOrder order = orderRepository.findByCode(code).orElse(null);
        if (order == null) return "redirect:/";
        model.addAttribute("order", order);
        return "order-success";
    }

    private void addCartToModel(Model model, ReceivingMethod receiving, String voucher) {
        List<CartService.CartLine> lines = cart.getLines();
        long subtotal = lines.stream().mapToLong(CartService.CartLine::getLineTotal).sum();
        long discount = shop.discountFor(subtotal, voucher);
        long shipping = orderService.shippingFor(receiving, subtotal - discount);
        model.addAttribute("lines", lines);
        model.addAttribute("subtotal", subtotal);
        model.addAttribute("discount", discount);
        model.addAttribute("shippingFee", shipping);
        // Phí ship nếu chọn giao hàng (để JavaScript đổi khi khách bấm Pickup/Delivery)
        model.addAttribute("deliveryFee", shop.shippingFeeFor(subtotal - discount));
        model.addAttribute("total", subtotal - discount + shipping);
        model.addAttribute("cartCount", cart.getCount());
        FormChecks.addOptions(model);
    }

    /** Chỉ quay lại đường dẫn nội bộ, tránh redirect ra trang lạ */
    private String safeBack(String referer) {
        if (referer == null) return "/";
        try {
            var uri = java.net.URI.create(referer);
            String path = uri.getRawPath() == null ? "/" : uri.getRawPath();
            String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
            return path.startsWith("/") && !path.startsWith("//") ? path + query : "/";
        } catch (IllegalArgumentException e) {
            return "/";
        }
    }
}
