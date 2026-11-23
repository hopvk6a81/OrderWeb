package com.bookstore.order.controller;

import com.bookstore.order.dto.LinkItemForm;
import com.bookstore.order.dto.LinkOrderForm;
import com.bookstore.order.service.LinkPreview;
import com.bookstore.order.service.LinkPreviewService;
import com.bookstore.order.model.Book;
import com.bookstore.order.model.LinkOrder;
import com.bookstore.order.repository.BookRepository;
import com.bookstore.order.repository.LinkOrderRepository;
import com.bookstore.order.repository.OrderRepository;
import com.bookstore.order.service.OrderService;
import com.bookstore.order.service.ShopSettings;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

@Controller
public class ShopController {

    static final String LAST_LINK_ORDER = "LAST_LINK_ORDER_CODE";

    private final BookRepository bookRepository;
    private final OrderRepository orderRepository;
    private final LinkOrderRepository linkOrderRepository;
    private final OrderService orderService;
    private final ShopSettings shop;
    private final LinkPreviewService previewService;

    public ShopController(BookRepository bookRepository, OrderRepository orderRepository,
                          LinkOrderRepository linkOrderRepository, OrderService orderService,
                          ShopSettings shop, LinkPreviewService previewService) {
        this.bookRepository = bookRepository;
        this.orderRepository = orderRepository;
        this.linkOrderRepository = linkOrderRepository;
        this.orderService = orderService;
        this.shop = shop;
        this.previewService = previewService;
    }

    /** Danh sách sách có sẵn */
    @GetMapping("/")
    public String index(@RequestParam(required = false) String q,
                        @RequestParam(required = false) String category,
                        Model model) {
        String query = (q == null || q.isBlank()) ? "" : q.trim();
        String cat = (category == null || category.isBlank()) ? "" : category;
        model.addAttribute("books", bookRepository.search(query, cat));
        model.addAttribute("categories", bookRepository.findCategories());
        model.addAttribute("q", query);
        model.addAttribute("category", cat);
        return "index";
    }

    @GetMapping("/books/{id}")
    public String bookDetail(@PathVariable Long id, Model model) {
        Book book = bookRepository.findById(id)
                .filter(Book::isActive)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("book", book);
        return "book-detail";
    }

    // ================= ORDER BY LINK =================

    @GetMapping("/order")
    public String linkOrderForm(@RequestParam(required = false) String title,
                                @RequestParam(required = false) String url,
                                Model model) {
        // Link truyền qua đường dẫn (vd từ trang sách) -> điền sẵn vào ô nhập link, khách bấm OK để thêm
        model.addAttribute("form", new LinkOrderForm());
        model.addAttribute("prefillUrl", url != null && url.matches("^https?://\\S+$") ? url : null);
        FormChecks.addOptions(model);
        return "link-order";
    }

    @PostMapping("/order")
    public String submitLinkOrder(@Valid @ModelAttribute("form") LinkOrderForm form,
                                  BindingResult result, Model model, HttpSession session) {
        FormChecks.validate(form, result, shop);

        // Giá bìa luôn do máy chủ đọc từ trang web – khách không nhập giá (tránh giả mạo).
        // Link nào không đọc được giá thì để "chờ báo giá", cửa hàng nhập giá sau.
        List<OrderService.ResolvedItem> resolved = new ArrayList<>();
        for (int i = 0; i < form.getItems().size(); i++) {
            LinkItemForm it = form.getItems().get(i);
            if (it.getUrl() == null || result.hasFieldErrors("items[" + i + "].url")) continue;
            LinkPreview p = previewService.preview(it.getUrl());
            // Giá theo đúng loại bìa khách chọn (bìa mềm / bìa cứng)
            LinkPreviewService.PriceChoice price = previewService.priceFor(p, it.getCoverType());
            if (price.unavailable()) {
                result.rejectValue("items[" + i + "].coverType", "unavailable",
                        "This book isn't sold in " + it.getCoverType().getLabel() + " on that website. Please choose another cover type.");
                continue;
            }
            String title = notBlank(it.getTitle()) ? it.getTitle().trim() : p.title();
            resolved.add(new OrderService.ResolvedItem(it.getUrl().trim(), title, p.imageUrl(),
                    price.amount(), price.currency(), price.vnd(), price.ok(), it.getCoverType(), it.getQuantity(),
                    p.isbn()));
        }

        if (result.hasErrors()) {
            FormChecks.addOptions(model);
            return "link-order";
        }
        LinkOrder order = orderService.placeLinkOrder(form, resolved);
        session.setAttribute(LAST_LINK_ORDER, order.getCode());
        return "redirect:/order/success?code=" + order.getCode();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    @GetMapping("/order/success")
    public String linkOrderSuccess(@RequestParam String code, HttpSession session, Model model) {
        // Chỉ xem được đơn vừa gửi trong cùng phiên (tránh người khác đoán mã)
        if (!code.equals(session.getAttribute(LAST_LINK_ORDER))) return "redirect:/track";
        LinkOrder order = linkOrderRepository.findByCode(code).orElse(null);
        if (order == null) return "redirect:/order";
        model.addAttribute("order", order);
        return "link-order-success";
    }

    /** Đường dẫn cũ */
    @GetMapping("/dat-sach-theo-yeu-cau")
    public String legacyRequest() {
        return "redirect:/order";
    }

    // ================= TRACK =================

    /** Khách tra cứu đơn (cả đơn giỏ hàng LB... và đơn theo link LK...) bằng mã + SĐT */
    @GetMapping("/track")
    public String track(@RequestParam(required = false) String code,
                        @RequestParam(required = false) String phone,
                        Model model) {
        if (code != null && phone != null && !code.isBlank() && !phone.isBlank()) {
            String c = code.trim().toUpperCase();
            String p = phone.trim();
            var order = orderRepository.findByCodeAndPhone(c, p);
            var linkOrder = order.isPresent() ? java.util.Optional.<LinkOrder>empty()
                    : linkOrderRepository.findByCodeAndPhone(c, p);
            order.ifPresent(o -> model.addAttribute("order", o));
            linkOrder.ifPresent(o -> model.addAttribute("linkOrder", o));
            if (order.isEmpty() && linkOrder.isEmpty()) model.addAttribute("notFound", true);
        }
        model.addAttribute("code", code);
        model.addAttribute("phone", phone);
        return "track";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }
}
