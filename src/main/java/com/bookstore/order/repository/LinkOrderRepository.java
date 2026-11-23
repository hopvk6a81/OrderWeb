package com.bookstore.order.repository;

import com.bookstore.order.model.LinkOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LinkOrderRepository extends JpaRepository<LinkOrder, Long> {

    List<LinkOrder> findAllByOrderByCreatedAtDesc();

    List<LinkOrder> findByStatusOrderByCreatedAtDesc(LinkOrder.Status status);

    Optional<LinkOrder> findByCode(String code);

    Optional<LinkOrder> findByCodeAndPhone(String code, String phone);

    boolean existsByCode(String code);
}
