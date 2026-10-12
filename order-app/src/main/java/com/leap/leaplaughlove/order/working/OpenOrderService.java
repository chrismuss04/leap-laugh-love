package com.leap.leaplaughlove.order.working;

import com.leap.leaplaughlove.order.account.Account;
import com.leap.leaplaughlove.order.account.AccountRepository;
import com.leap.leaplaughlove.order.order.Order;
import com.leap.leaplaughlove.order.order.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lists, cancels and expires a client's OPEN orders. Every status change is made under the
 * order's row lock and only from OPEN, so a cancel and {@link OrderTriggerEngine} filling the
 * same order can't both succeed: whichever locks it first wins.
 */
@Service
public class OpenOrderService {

    private static final Logger log = LoggerFactory.getLogger(OpenOrderService.class);

    private final OrderRepository orderRepository;
    private final AccountRepository accountRepository;
    private final TransactionTemplate transactionTemplate;
    private final OrderUpdateBroadcaster broadcaster;

    /**
     * Constructs an OpenOrderService.
     * @param orderRepository loads and locks orders
     * @param accountRepository checks an order's account belongs to the client
     * @param transactionTemplate runs each expiry in its own transaction
     * @param broadcaster tells clients, as it happens, that an order of theirs expired
     */
    public OpenOrderService(OrderRepository orderRepository, AccountRepository accountRepository,
                            TransactionTemplate transactionTemplate, OrderUpdateBroadcaster broadcaster) {
        this.orderRepository = orderRepository;
        this.accountRepository = accountRepository;
        this.transactionTemplate = transactionTemplate;
        this.broadcaster = broadcaster;
    }

    /**
     * Lists the client's OPEN orders across their accounts, newest first.
     * @param clientId the authenticated client
     * @return the open orders
     */
    @Transactional(readOnly = true)
    public List<OpenOrderItem> listOpen(UUID clientId) {
        return orderRepository.findOpenByClientId(clientId).stream().map(OpenOrderItem::of).toList();
    }

    /**
     * Cancels one of the client's OPEN orders, releasing the cash or shares it held.
     * @param clientId the authenticated client
     * @param orderId the order to cancel
     * @return the cancelled order
     * @throws ResponseStatusException not found (404) if the client has no such order, conflict
     *         (409) if it is no longer OPEN, e.g. it filled first
     */
    @Transactional
    public OpenOrderItem cancel(UUID clientId, UUID orderId) {
        Order order = lockOwned(clientId, orderId);
        requireOpen(order, "cancelled");
        order.markCancelled(OffsetDateTime.now());
        return OpenOrderItem.of(order);
    }

    /**
     * Loads one of the client's orders with its row lock, in the caller's transaction.
     * @param clientId the authenticated client
     * @param orderId the order
     * @return the locked order
     * @throws ResponseStatusException not found (404) if the client has no such order
     */
    Order lockOwned(UUID clientId, UUID orderId) {
        return orderRepository.findByIdForUpdate(orderId)
                .filter(found -> accountRepository.findById(found.getAccountId())
                        .map(Account::getClientId)
                        .filter(clientId::equals)
                        .isPresent())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
    }

    /**
     * Refuses an order that isn't working any more, saying where it went.
     * @param order the locked order
     * @param action what was asked of it, in the past tense: "cancelled" or "changed"
     * @throws ResponseStatusException conflict (409) if the order is not OPEN
     */
    static void requireOpen(Order order, String action) {
        if (order.getStatus() == Order.Status.OPEN) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, switch (order.getStatus()) {
            case ACCEPTED, FILLED -> "This order has already executed and can no longer be " + action;
            case CANCELLED -> "This order is already cancelled";
            case EXPIRED -> "This order has already expired";
            default -> "This order is no longer open";
        });
    }

    /**
     * Expires every OPEN order whose time in force has run out, each in its own transaction so
     * one failure doesn't hold up the rest.
     * @return how many orders were expired
     */
    public int expireDue() {
        OffsetDateTime now = OffsetDateTime.now();
        int expired = 0;
        List<Map.Entry<UUID, OrderUpdate>> pending = new ArrayList<>();
        for (UUID orderId : orderRepository.findOpenExpiredBy(now)) {
            try {
                OrderUpdate update = transactionTemplate.execute(status -> orderRepository.findByIdForUpdate(orderId)
                        .filter(order -> order.getStatus() == Order.Status.OPEN)
                        .map(order -> {
                            order.markExpired(now);
                            return new Expired(OrderUpdate.of(order, Order.Status.EXPIRED, null, null),
                                    order.getAccountId());
                        })
                        .map(expiredOrder -> {
                            accountRepository.findById(expiredOrder.accountId()).map(Account::getClientId)
                                    .ifPresent(client -> pending.add(Map.entry(client, expiredOrder.update())));
                            return expiredOrder.update();
                        })
                        .orElse(null));
                if (update != null) {
                    expired++;
                }
            } catch (RuntimeException ex) {
                log.warn("Could not expire order {}; will retry: {}", orderId, ex.getMessage());
            }
        }
        // Told after each expiry has committed, never from inside its transaction.
        pending.forEach(entry -> broadcaster.publish(entry.getKey(), entry.getValue()));
        if (expired > 0) {
            log.info("Expired {} open order(s) past their time in force", expired);
        }
        return expired;
    }

    /** An order that has just expired, and the account it belongs to. */
    private record Expired(OrderUpdate update, UUID accountId) {
    }
}
