package com.bookstore.order.repository;

import com.bookstore.order.model.CustomerOrder;
import com.bookstore.order.model.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<CustomerOrder, Long> {

    Optional<CustomerOrder> findByCodeAndPhone(String code, String phone);

    Optional<CustomerOrder> findByCode(String code);

    boolean existsByCode(String code);

    List<CustomerOrder> findAllByOrderByCreatedAtDesc();

    List<CustomerOrder> findByStatusOrderByCreatedAtDesc(OrderStatus status);
}
