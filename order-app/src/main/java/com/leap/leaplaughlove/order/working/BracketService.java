package com.leap.leaplaughlove.order.working;

import com.leap.leaplaughlove.order.events.OrderEventPublisher;
import com.leap.leaplaughlove.order.order.Order;
import com.leap.leaplaughlove.order.order.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Keeps the two exit orders a bracket opens from both executing. When one leg fills, the other,
 * which sells the same shares, is cancelled.
 *
 * <p>Each cancel runs in a transaction of its own, after the fill's has committed, and locks the
 * group through {@link OrderRepository#findGroupForUpdate} so legs are always locked lowest ID
 * first. {@code OrderSubmissionService#acceptOpenOrder} will not execute a leg while its sibling
 * is ACCEPTED or FILLED, so if this cancel fails the sibling still cannot execute, and the next
 * attempt to fill it cancels it.
 */
@Service
public class BracketService {

    private static final Logger log = LoggerFactory.getLogger(BracketService.class);

    private final OrderRepository orderRepository;
    private final TransactionTemplate newTransaction;
    private final OrderEventPublisher orderEventPublisher;

    /**
     * Constructs a BracketService.
     * @param orderRepository locks and cancels the group's orders
     * @param transactionManager runs each cancel in a transaction of its own
     * @param orderEventPublisher reports each cancelled order to reporting
     */
    public BracketService(OrderRepository orderRepository, PlatformTransactionManager transactionManager,
                          OrderEventPublisher orderEventPublisher) {
        this.orderRepository = orderRepository;
        this.orderEventPublisher = orderEventPublisher;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Cancels every other OPEN order in the filled order's group. Never throws: the fill is
     * already booked, and a failed cancel is made good the next time the sibling is looked at.
     * @param filledOrderId the leg that executed
     * @param ocoGroupId its group
     * @return how many orders were cancelled
     */
    public int cancelSiblings(UUID filledOrderId, UUID ocoGroupId) {
        if (ocoGroupId == null) {
            return 0;
        }
        try {
            Integer cancelled = newTransaction.execute(status -> {
                OffsetDateTime now = OffsetDateTime.now();
                int count = 0;
                for (Order leg : orderRepository.findGroupForUpdate(ocoGroupId)) {
                    if (!leg.getOrderId().equals(filledOrderId) && leg.getStatus() == Order.Status.OPEN) {
                        leg.markCancelled(now);
                        orderEventPublisher.publishCancelled(leg);
                        count++;
                    }
                }
                return count;
            });
            if (cancelled != null && cancelled > 0) {
                log.info("Order {} executed; cancelled {} linked exit order(s)", filledOrderId, cancelled);
            }
            return cancelled != null ? cancelled : 0;
        } catch (RuntimeException ex) {
            log.warn("Could not cancel the exit orders linked to {}; they cannot execute meanwhile: {}",
                    filledOrderId, ex.getMessage());
            return 0;
        }
    }
}
