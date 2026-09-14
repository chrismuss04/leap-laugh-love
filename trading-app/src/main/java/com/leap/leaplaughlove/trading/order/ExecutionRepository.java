package com.leap.leaplaughlove.trading.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ExecutionRepository extends JpaRepository<Execution, UUID> {
    List<Execution> findByOrder_OrderIdInOrderByExecutedAtAsc(Collection<UUID> orderIds);
}
