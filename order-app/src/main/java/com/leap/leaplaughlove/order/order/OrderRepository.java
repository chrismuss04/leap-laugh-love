package com.leap.leaplaughlove.order.order;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
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
     * most recently completed first (see {@link Order#getCompletedAt()}), then by order ID: a limit
     * order placed yesterday that filled today is listed with today's trades.
     * @param clientId the client ID
     * @param pageable the pagination information
     * @return a page of orders for the client
     */
    @Query("SELECT o FROM Order o, Account a WHERE o.accountId = a.accountId AND a.clientId = :clientId " +
           "ORDER BY COALESCE(o.filledAt, o.rejectedAt, o.cancelledAt, o.expiredAt, o.submittedAt) DESC, o.orderId DESC")
    Page<Order> findByClientId(@Param("clientId") UUID clientId, Pageable pageable);

    /**
     * Retrieves a paginated list of orders for the specified client ID completed within a date
     * range (see {@link Order#getCompletedAt()}), most recently completed first, then by order ID.
     * @param clientId the client ID
     * @param from the start of the date range (inclusive)
     * @param to the end of the date range (exclusive)
     * @param pageable the pagination information
     * @return a page of orders for the client in the date range
     */
    @Query("SELECT o FROM Order o, Account a WHERE o.accountId = a.accountId AND a.clientId = :clientId " +
           "AND COALESCE(o.filledAt, o.rejectedAt, o.cancelledAt, o.expiredAt, o.submittedAt) >= :from AND COALESCE(o.filledAt, o.rejectedAt, o.cancelledAt, o.expiredAt, o.submittedAt) < :to " +
           "ORDER BY COALESCE(o.filledAt, o.rejectedAt, o.cancelledAt, o.expiredAt, o.submittedAt) DESC, o.orderId DESC")
    Page<Order> findByClientIdCompletedBetween(@Param("clientId") UUID clientId,
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
    @Query("SELECT o FROM Order o JOIN FETCH o.instrument " +
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

    /**
     * Finds every OPEN order, waiting for the market to reach its price, oldest first so earlier
     * orders get the first chance at a price.
     * @return the open orders, with their instruments loaded
     */
    @Query("SELECT o FROM Order o JOIN FETCH o.instrument " +
           "WHERE o.status = com.leap.leaplaughlove.order.order.Order.Status.OPEN " +
           "ORDER BY o.submittedAt, o.orderId")
    List<Order> findOpen();

    /**
     * Finds the OPEN orders whose time in force ran out before the given time.
     * @param now the current time
     * @return the IDs of the orders to expire
     */
    @Query("SELECT o.orderId FROM Order o " +
           "WHERE o.status = com.leap.leaplaughlove.order.order.Order.Status.OPEN " +
           "AND o.expiresAt IS NOT NULL AND o.expiresAt <= :now")
    List<UUID> findOpenExpiredBy(@Param("now") OffsetDateTime now);

    /**
     * Finds a client's OPEN orders across their accounts, newest first.
     * @param clientId the client ID
     * @return the open orders, with their instruments loaded
     */
    @Query("SELECT o FROM Order o JOIN FETCH o.instrument, Account a " +
           "WHERE o.accountId = a.accountId AND a.clientId = :clientId " +
           "AND o.status = com.leap.leaplaughlove.order.order.Order.Status.OPEN " +
           "ORDER BY o.submittedAt DESC, o.orderId DESC")
    List<Order> findOpenByClientId(@Param("clientId") UUID clientId);

    /**
     * Sums the cash an account's OPEN buy orders hold back from its buying power.
     * @param accountId the account
     * @param excludedOrderId an order to leave out, e.g. the one now being filled
     * @return the cash held, zero when there is none
     */
    @Query("SELECT COALESCE(SUM(o.reservedCash), 0) FROM Order o WHERE o.accountId = :accountId " +
           "AND o.status = com.leap.leaplaughlove.order.order.Order.Status.OPEN " +
           "AND o.side = com.leap.leaplaughlove.order.order.Order.Side.BUY " +
           "AND o.orderId <> :excludedOrderId")
    BigDecimal sumReservedCash(@Param("accountId") UUID accountId,
                               @Param("excludedOrderId") UUID excludedOrderId);

    /**
     * Sums the shares of one instrument an account's OPEN sell orders have already promised. The two
     * exits of a bracket sell the same shares, so a group of linked orders counts once, as its
     * largest leg, instead of once per leg.
     * @param accountId the account
     * @param instrumentId the instrument
     * @param excludedOrderId an order to leave out, e.g. the one now being filled; so is the rest of
     *                        its group of linked orders, whose shares it is the one to sell
     * @return the shares held, zero when there are none
     */
    @Query(value = "SELECT COALESCE(SUM(g.quantity), 0) FROM ("
            + "SELECT MAX(o.quantity) AS quantity FROM trading.orders o "
            + "WHERE o.account_id = :accountId AND o.instrument_id = :instrumentId "
            + "AND o.status = 'OPEN' AND o.side = 'SELL' AND o.order_id <> :excludedOrderId "
            + "AND (o.oco_group_id IS NULL OR o.oco_group_id NOT IN ("
            + "SELECT x.oco_group_id FROM trading.orders x "
            + "WHERE x.order_id = :excludedOrderId AND x.oco_group_id IS NOT NULL)) "
            + "GROUP BY COALESCE(o.oco_group_id, o.order_id)) g",
           nativeQuery = true)
    Long sumOpenSellQuantity(@Param("accountId") UUID accountId,
                             @Param("instrumentId") UUID instrumentId,
                             @Param("excludedOrderId") UUID excludedOrderId);

    /**
     * Tells whether a bracket buy has already opened its exit orders.
     * @param parentOrderId the buy
     * @return true if any order was opened for it
     */
    boolean existsByParentOrderId(UUID parentOrderId);

    /**
     * Finds the group a linked order belongs to, without loading the order itself.
     * @param orderId the order
     * @return the group ID, or null if the order is not linked to others (or does not exist)
     */
    @Query("SELECT o.ocoGroupId FROM Order o WHERE o.orderId = :orderId")
    UUID findOcoGroupId(@Param("orderId") UUID orderId);

    /**
     * Loads every order of a one-cancels-other group with row locks taken in ascending ID order.
     * Anything that needs more than one leg locks them through here, so two of them never wait on
     * each other's rows in opposite orders.
     * @param ocoGroupId the group
     * @return the group's orders, lowest ID first
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.ocoGroupId = :ocoGroupId ORDER BY o.orderId")
    List<Order> findGroupForUpdate(@Param("ocoGroupId") UUID ocoGroupId);

    /**
     * Locks the account's row for the rest of the transaction, so two orders on one account
     * can't both count the same cash or shares as free.
     * @param accountId the account to lock
     * @return the account ID if the account exists
     */
    @Query(value = "SELECT account_id FROM trading.accounts WHERE account_id = :accountId FOR UPDATE",
           nativeQuery = true)
    List<Object> lockAccount(@Param("accountId") UUID accountId);
}
