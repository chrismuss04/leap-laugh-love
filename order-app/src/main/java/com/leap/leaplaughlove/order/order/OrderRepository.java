package com.leap.leaplaughlove.order.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.UUID;

/**
 * Repository interface for accessing Order entities from the database.
 * Provides methods for retrieving orders by account ID or client ID with pagination and sorting.
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

    /**
     * Retrieves a paginated list of orders for the specified client ID across all accounts,
     * sorted by submission date and order ID in descending order.
     * @param clientId the client ID
     * @param pageable the pagination information
     * @return a page of orders for the client
     */
    @Query("SELECT o FROM Order o WHERE o.account.clientId = :clientId " +
           "ORDER BY o.submittedAt DESC, o.orderId DESC")
    Page<Order> findByClientId(@Param("clientId") UUID clientId, Pageable pageable);

    /**
     * Retrieves a paginated list of orders for the specified client ID submitted within a date range,
     * sorted by submission date and order ID in descending order.
     * @param clientId the client ID
     * @param from the start of the date range (inclusive)
     * @param to the end of the date range (exclusive)
     * @param pageable the pagination information
     * @return a page of orders for the client in the date range
     */
    @Query("SELECT o FROM Order o WHERE o.account.clientId = :clientId " +
           "AND o.submittedAt >= :from AND o.submittedAt < :to " +
           "ORDER BY o.submittedAt DESC, o.orderId DESC")
    Page<Order> findByClientIdSubmittedBetween(@Param("clientId") UUID clientId,
                                               @Param("from") OffsetDateTime from,
                                               @Param("to") OffsetDateTime to,
                                               Pageable pageable);
}
