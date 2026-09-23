package com.leap.leaplaughlove.trading.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface ExecutionRepository extends JpaRepository<Execution, UUID> {
    List<Execution> findByOrder_OrderIdIn(Collection<UUID> orderIds);

    boolean existsByOrder_OrderId(UUID orderId);
}

