package com.bookstore.order.controller;

import com.bookstore.order.model.Book;
import com.bookstore.order.model.LinkOrder;
import com.bookstore.order.model.OrderStatus;
import com.bookstore.order.repository.BookRepository;
import com.bookstore.order.repository.LinkOrderRepository;
import com.bookstore.order.repository.OrderRepository;
import com.bookstore.order.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin")
public class AdminController {

    private final OrderRepository orderRepository;
    private final LinkOrderRepository linkOrderRepository;
    private final BookRepository bookRepository;
    private final OrderService orderService;

    public AdminController(OrderRepository orderRepository, LinkOrderRepository linkOrderRepository,
                           BookRepository bookRepository, OrderService orderService) {
        this.orderRepository = orderRepository;
        this.linkOrderRepository = linkOrderRepository;
        this.bookRepository = bookRepository;
        this.orderService = orderService;
    }

    @GetMapping({"", "/"})
    public String home() {
        return "redirect:/admin/link-orders";
    }

    // ================= ĐƠN THEO LINK =================

    @GetMapping("/link-orders")
    public String linkOrders(@RequestParam(required = false) LinkOrder.Status status, Model model) {
        model.addAttribute("orders", status == null
                ? linkOrderRepository.findAllByOrderByCreatedAtDesc()
                : linkOrderRepository.findByStatusOrderByCreatedAtDesc(status));
        model.addAttribute("statuses", LinkOrder.Status.values());
        model.addAttribute("currentStatus", status);
        return "admin/link-orders";
    }

    @GetMapping("/link-orders/{id}")
    public String linkOrderDetail(@PathVariable Long id, Model model) {
        model.addAttribute("order", linkOrderRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
        model.addAttribute("statuses", LinkOrder.Status.values());
        model.addAttribute("coverTypes", com.bookstore.order.model.CoverType.values());
        return "admin/link-order-detail";
    }

    @PostMapping("/link-orders/{id}")
    public String updateLinkOrder(@PathVariable Long id,
                                  @RequestParam LinkOrder.Status status,
                                  @RequestParam(required = false) String shopNote,
                                  @RequestParam(name = "originalPrice", required = false) java.util.List<java.math.BigDecimal> amounts,
                                  @RequestParam(name = "currency", required = false) java.util.List<String> currencies,
                                  @RequestParam(name = "coverType", required = false) java.util.List<com.bookstore.order.model.CoverType> coverTypes,
                                  RedirectAttributes ra) {
        orderService.updateLinkOrder(id, status, shopNote, amounts, currencies, coverTypes);
        ra.addFlashAttribute("success", "Đã lưu đơn");
        return "redirect:/admin/link-orders/" + id;
    }

    // ================= ĐƠN GIỎ HÀNG =================

    @GetMapping("/orders")
    public String orders(@RequestParam(required = false) OrderStatus status, Model model) {
        model.addAttribute("orders", status == null
                ? orderRepository.findAllByOrderByCreatedAtDesc()
                : orderRepository.findByStatusOrderByCreatedAtDesc(status));
        model.addAttribute("statuses", OrderStatus.values());
        model.addAttribute("currentStatus", status);
        return "admin/orders";
    }

    @GetMapping("/orders/{id}")
    public String orderDetail(@PathVariable Long id, Model model) {
        model.addAttribute("order", orderRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
        model.addAttribute("statuses", OrderStatus.values());
        return "admin/order-detail";
    }

    @PostMapping("/orders/{id}/status")
    public String updateOrderStatus(@PathVariable Long id, @RequestParam OrderStatus status,
                                    RedirectAttributes ra) {
        orderService.updateStatus(id, status);
        ra.addFlashAttribute("success", "Đã cập nhật trạng thái đơn hàng");
        return "redirect:/admin/orders/" + id;
    }

    // ================= SÁCH =================

    @GetMapping("/books")
    public String books(Model model) {
        model.addAttribute("books", bookRepository.findAll(Sort.by(Sort.Direction.DESC, "id")));
        return "admin/books";
    }

    @GetMapping("/books/new")
    public String newBook(Model model) {
        model.addAttribute("book", new Book());
        return "admin/book-form";
    }

    @GetMapping("/books/{id}/edit")
    public String editBook(@PathVariable Long id, Model model) {
        model.addAttribute("book", bookRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
        return "admin/book-form";
    }

    @PostMapping("/books/save")
    public String saveBook(@Valid @ModelAttribute("book") Book book, BindingResult result,
                           RedirectAttributes ra) {
        if (result.hasErrors()) return "admin/book-form";
        bookRepository.save(book);
        ra.addFlashAttribute("success", "Đã lưu sách \"" + book.getTitle() + "\"");
        return "redirect:/admin/books";
    }

    /** Ẩn/hiện sách (không xóa hẳn để giữ lịch sử đơn hàng) */
    @PostMapping("/books/{id}/toggle")
    public String toggleBook(@PathVariable Long id) {
        Book b = bookRepository.findById(id).orElseThrow();
        b.setActive(!b.isActive());
        bookRepository.save(b);
        return "redirect:/admin/books";
    }
}
