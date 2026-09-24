package com.leap.leaplaughlove.order.execution;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for managing Execution entities.
 * Provides methods to perform CRUD operations and custom queries on executions.
 * */
@Repository
public interface ExecutionRepository extends JpaRepository<Execution, UUID> {
    /**
     * Finds all executions associated with the given order IDs.
     * @param orderIds the collection of order IDs to search for executions
     * @return a list of executions associated with the given order IDs
     */
    List<Execution> findByOrder_OrderIdIn(Collection<UUID> orderIds);

    /**
     * Checks if an execution exists for the given order ID.
     * @param orderId the order ID
     * @return true if an execution exists, false otherwise
     */
    boolean existsByOrder_OrderId(UUID orderId);

    /**
     * Finds the order's execution in the given status, if it has one.
     * @param orderId the order ID
     * @param status the execution status
     * @return the execution, if any
     */
    Optional<Execution> findFirstByOrder_OrderIdAndStatus(UUID orderId, Execution.Status status);
}

