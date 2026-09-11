package com.leap.leaplaughlove.trading.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Interface to represent the repository for orders in the trading system.
 * This repository provides methods to perform CRUD operations and custom queries on orders.
 */
public interface OrderRepository extends JpaRepository<Order, UUID> {
    Page<Order> findByAccount_ClientIdOrderBySubmittedAtDescOrderIdDesc(UUID clientId, Pageable pageable);
}
