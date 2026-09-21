package com.leap.leaplaughlove.order.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.UUID;

/**
 * Repository interface for accessing Order entities from the database.
 * Provides methods for retrieving orders by account ID with pagination and sorting.
 */
@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {
    /**
     * Retrieves a paginated list of orders for the specified account IDs, sorted by submission date and order ID in descending order.
     * @param accountIds the collection of account IDs to filter orders by
     * @param pageable the pagination information
     * @return a page of orders matching the specified account IDs
     */
    Page<Order> findByAccountIdInOrderBySubmittedAtDescOrderIdDesc(Collection<UUID> accountIds, Pageable pageable);
    /**
     * Retrieves a paginated list of orders for the specified account ID, sorted by submission date and order ID in descending order.
     * @param accountId the account ID to filter orders by
     * @param pageable the pagination information
     * @return a page of orders matching the specified account ID
     */
    Page<Order> findByAccountIdOrderBySubmittedAtDescOrderIdDesc(UUID accountId, Pageable pageable);
}

