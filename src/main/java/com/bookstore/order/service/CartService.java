package com.bookstore.order.service;

import com.bookstore.order.model.Book;
import com.bookstore.order.repository.BookRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.SessionScope;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Giỏ hàng lưu theo phiên (session) của từng khách. Không cần đăng nhập.
 */
@Component
@SessionScope
public class CartService implements Serializable {

    /** bookId -> số lượng */
    private final Map<Long, Integer> items = new LinkedHashMap<>();

    private final transient BookRepository bookRepository;

    public CartService(BookRepository bookRepository) {
        this.bookRepository = bookRepository;
    }

    public void add(Long bookId, int quantity) {
        if (quantity <= 0) return;
        items.merge(bookId, quantity, Integer::sum);
    }

    public void update(Long bookId, int quantity) {
        if (quantity <= 0) items.remove(bookId);
        else items.put(bookId, quantity);
    }

    public void remove(Long bookId) { items.remove(bookId); }

    public void clear() { items.clear(); }

    public boolean isEmpty() { return items.isEmpty(); }

    public int getCount() {
        return items.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Lấy danh sách dòng giỏ hàng kèm thông tin sách mới nhất từ CSDL */
    public List<CartLine> getLines() {
        List<CartLine> lines = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : new LinkedHashMap<>(items).entrySet()) {
            bookRepository.findById(e.getKey())
                    .filter(Book::isActive)
                    .ifPresentOrElse(
                            book -> lines.add(new CartLine(book, e.getValue())),
                            () -> items.remove(e.getKey()));
        }
        return lines;
    }

    public long getSubtotal() {
        return getLines().stream().mapToLong(CartLine::getLineTotal).sum();
    }

    public static class CartLine {
        private final Book book;
        private final int quantity;

        public CartLine(Book book, int quantity) {
            this.book = book;
            this.quantity = quantity;
        }

        public Book getBook() { return book; }
        public int getQuantity() { return quantity; }
        public long getLineTotal() { return book.getPrice() * quantity; }
    }
}
