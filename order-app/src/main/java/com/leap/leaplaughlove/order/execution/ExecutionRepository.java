package com.leap.leaplaughlove.order.execution;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
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
}

