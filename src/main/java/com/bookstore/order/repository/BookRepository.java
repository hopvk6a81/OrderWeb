package com.bookstore.order.repository;

import com.bookstore.order.model.Book;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BookRepository extends JpaRepository<Book, Long> {

    @Query("""
            SELECT b FROM Book b
            WHERE b.active = true
              AND (:q = '' OR LOWER(b.title) LIKE LOWER(CONCAT('%', :q, '%'))
                              OR LOWER(b.author) LIKE LOWER(CONCAT('%', :q, '%')))
              AND (:category = '' OR b.category = :category)
            ORDER BY b.id DESC
            """)
    List<Book> search(@Param("q") String q, @Param("category") String category);

    @Query("SELECT DISTINCT b.category FROM Book b WHERE b.active = true AND b.category IS NOT NULL ORDER BY b.category")
    List<String> findCategories();
}
