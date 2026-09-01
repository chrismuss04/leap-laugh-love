package com.leap.leaplaughlove.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    // Tie-break by orderId so paginated results stay stable across pages with equal timestamps.
    Page<Order> findByAccount_ClientIdOrderBySubmittedAtDescOrderIdDesc(UUID clientId, Pageable pageable);
}
