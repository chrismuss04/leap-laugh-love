package com.leap.leaplaughlove.order.order;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.leap.leaplaughlove.order.account.Account;

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
    @Query("SELECT o FROM Order o, Account a WHERE o.accountId = a.accountId AND a.clientId = :clientId " +
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
    @Query("SELECT o FROM Order o, Account a WHERE o.accountId = a.accountId AND a.clientId = :clientId " +
           "AND o.submittedAt >= :from AND o.submittedAt < :to " +
           "ORDER BY o.submittedAt DESC, o.orderId DESC")
    Page<Order> findByClientIdSubmittedBetween(@Param("clientId") UUID clientId,
                                               @Param("from") OffsetDateTime from,
                                               @Param("to") OffsetDateTime to,
                                               Pageable pageable);

    /**
     * Finds orders in the given status with no position movement, oldest fill first. For FILLED
     * these are the fills not fully booked yet: the seed data's orders, which start with no
     * execution, and any whose booking stopped after the execution was written. A fill's
     * position movement is its last step (see FillRecorder), and live submission only marks an
     * order FILLED after writing it, so live orders never show up here.
     * @param status the order status to look for
     * @return the matching orders, by fill time then id
     */
    @Query("SELECT o FROM Order o JOIN FETCH o.instrument WHERE o.status = :status " +
           "AND NOT EXISTS (SELECT m FROM PositionMovement m WHERE m.orderId = o.orderId) " +
           "ORDER BY o.filledAt, o.orderId")
    List<Order> findWithoutPositionMovementByStatus(@Param("status") Order.Status status);

    /**
     * Finds live fills whose booking stopped part-way: orders still ACCEPTED although their
     * FILLED execution is committed, because settling them with account-app failed with an
     * unknown outcome, or the app stopped before recording that it succeeded. Only orders
     * accepted before the cutoff are returned, so a submission still in flight is left alone.
     * @param acceptedBefore only orders accepted before this are returned
     * @return the stuck orders, oldest first
     */
    @Query("SELECT o FROM Order o JOIN FETCH o.account JOIN FETCH o.instrument " +
           "WHERE o.status = com.leap.leaplaughlove.order.order.Order.Status.ACCEPTED " +
           "AND o.acceptedAt < :acceptedBefore " +
           "AND EXISTS (SELECT e FROM Execution e WHERE e.order = o " +
           "AND e.status = com.leap.leaplaughlove.order.execution.Execution.Status.FILLED) " +
           "ORDER BY o.acceptedAt, o.orderId")
    List<Order> findAcceptedWithFilledExecution(@Param("acceptedBefore") OffsetDateTime acceptedBefore);

    /**
     * Loads an order with a row lock, so two processes booking the same fill serialize.
     * @param orderId the order to lock
     * @return the locked order, if it exists
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.orderId = :orderId")
    Optional<Order> findByIdForUpdate(@Param("orderId") UUID orderId);
}
