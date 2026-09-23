package com.leap.leaplaughlove.trading.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Interface to represent the repository for orders in the trading system.
 * This repository provides methods to perform CRUD operations and custom queries on orders.
 */
public interface OrderRepository extends JpaRepository<Order, UUID> {

    @Query("SELECT o FROM Order o WHERE o.account.clientId = :clientId " +
           "ORDER BY o.submittedAt DESC, o.orderId DESC")
    Page<Order> findByClientId(@Param("clientId") UUID clientId, Pageable pageable);

    @Query("SELECT o FROM Order o WHERE o.account.clientId = :clientId " +
           "AND o.submittedAt >= :from AND o.submittedAt < :to " +
           "ORDER BY o.submittedAt DESC, o.orderId DESC")
    Page<Order> findByClientIdSubmittedBetween(@Param("clientId") UUID clientId,
                                               @Param("from") OffsetDateTime from,
                                               @Param("to") OffsetDateTime to,
                                               Pageable pageable);

    /**
     * Finds orders in the given status that have no execution at all, oldest fill first. For
     * FILLED this is the seed data's orders whose fills haven't been booked yet - live
     * submission writes the order and its execution in one transaction, so it never leaves one.
     * @param status the order status to look for
     * @return the matching orders, by fill time then id
     */
    @Query("SELECT o FROM Order o JOIN FETCH o.account JOIN FETCH o.instrument WHERE o.status = :status " +
           "AND NOT EXISTS (SELECT e FROM Execution e WHERE e.order = o) " +
           "ORDER BY o.filledAt, o.orderId")
    List<Order> findWithoutExecutionByStatus(@Param("status") Order.Status status);

    /**
     * Loads an order with a row lock, so two processes booking the same fill serialize.
     * @param orderId the order to lock
     * @return the locked order, if it exists
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.orderId = :orderId")
    Optional<Order> findByIdForUpdate(@Param("orderId") UUID orderId);
}
