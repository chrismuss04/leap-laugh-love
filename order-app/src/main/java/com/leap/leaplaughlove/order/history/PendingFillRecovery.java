package com.leap.leaplaughlove.order.history;

import com.leap.leaplaughlove.common.security.JwtService;
import com.leap.leaplaughlove.order.execution.Execution;
import com.leap.leaplaughlove.order.execution.ExecutionRepository;
import com.leap.leaplaughlove.order.order.Order;
import com.leap.leaplaughlove.order.order.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Finishes booking live fills that a partial platform failure left part-way, so the trade record
 * always ends up agreeing with cash and holdings.
 *
 * <p>A live fill is booked in FillRecorder's three steps, committed separately. If account-app
 * can't be reached or times out while settling, or this app stops or loses its database between
 * the steps, the order is left ACCEPTED with a committed FILLED execution. Whether cash and
 * holdings moved is then unknown. This job finds those orders and settles them again - which
 * account-app does only once, answering a repeat from what it already booked - then writes the
 * position movement and marks the order FILLED. If account-app refuses the settlement outright,
 * the order is marked REJECTED instead, as live submission would have done.
 *
 * <p>Runs shortly after startup, which recovers anything a crash left behind, and then on a fixed
 * delay while the app is up. A failure leaves the order as it was, to be retried on the next run.
 */
@Service
@ConditionalOnProperty(prefix = "trading.fill-recovery", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class PendingFillRecovery {

    private static final Logger log = LoggerFactory.getLogger(PendingFillRecovery.class);

    private final OrderRepository orderRepository;
    private final ExecutionRepository executionRepository;
    private final FillRecorder fillRecorder;
    private final JwtService jwtService;
    private final TransactionTemplate transactionTemplate;
    private final long graceSeconds;

    public PendingFillRecovery(OrderRepository orderRepository,
                               ExecutionRepository executionRepository,
                               FillRecorder fillRecorder,
                               JwtService jwtService,
                               TransactionTemplate transactionTemplate,
                               @Value("${trading.fill-recovery.grace-seconds:30}") long graceSeconds) {
        this.orderRepository = orderRepository;
        this.executionRepository = executionRepository;
        this.fillRecorder = fillRecorder;
        this.jwtService = jwtService;
        this.transactionTemplate = transactionTemplate;
        this.graceSeconds = graceSeconds;
    }

    /**
     * Scheduled entry point; never throws, so one bad run doesn't stop later ones.
     */
    @Scheduled(initialDelayString = "${trading.fill-recovery.initial-delay-ms:15000}",
               fixedDelayString = "${trading.fill-recovery.interval-ms:30000}")
    public void run() {
        try {
            recoverPendingFills();
        } catch (RuntimeException ex) {
            log.warn("Recovering pending fills failed: {}", ex.getMessage());
        }
    }

    /**
     * Settles and finishes every live fill left part-way that is older than the grace period.
     * @return how many stuck fills are still unresolved after this run
     */
    int recoverPendingFills() {
        List<Order> pending = orderRepository.findAcceptedWithFilledExecution(
                OffsetDateTime.now().minusSeconds(graceSeconds));
        int unresolved = 0;
        for (Order order : pending) {
            if (!recover(order)) {
                unresolved++;
            }
        }
        if (!pending.isEmpty()) {
            log.info("Recovered {} of {} fill(s) left part-way by a platform failure",
                    pending.size() - unresolved, pending.size());
        }
        return unresolved;
    }

    /**
     * Settles one stuck fill and finishes or rejects its order.
     * @param order an ACCEPTED order with a committed FILLED execution
     * @return true if the order is now FILLED or REJECTED, false if it is still pending
     */
    private boolean recover(Order order) {
        Optional<Execution> filled = executionRepository.findFirstByOrder_OrderIdAndStatus(
                order.getOrderId(), Execution.Status.FILLED);
        if (filled.isEmpty()) {
            return true;
        }
        Execution execution = filled.get();

        try {
            // No request is behind this, so there is no caller's token to forward; sign as the
            // order's owner, whose request this fill was.
            fillRecorder.settle(order, execution, jwtService.generateToken(order.getAccount().getClientId(), null));
        } catch (RuntimeException ex) {
            if (!FillRecorder.isRefused(ex)) {
                log.warn("Order {} is still waiting to settle; will retry: {}", order.getOrderId(), ex.getMessage());
                return false;
            }
            String reason = "Settlement failed: " + FillRecorder.refusalReason(ex);
            transactionTemplate.executeWithoutResult(status -> orderRepository.findByIdForUpdate(order.getOrderId())
                    .filter(locked -> locked.getStatus() == Order.Status.ACCEPTED)
                    .ifPresent(locked -> locked.markRejected(reason, OffsetDateTime.now())));
            log.warn("Order {} was refused by account-app on recovery and is now REJECTED: {}",
                    order.getOrderId(), ex.getMessage());
            return true;
        }

        transactionTemplate.executeWithoutResult(status -> orderRepository.findByIdForUpdate(order.getOrderId())
                .filter(locked -> locked.getStatus() == Order.Status.ACCEPTED)
                .ifPresent(locked -> fillRecorder.completeFill(locked, execution)));
        log.info("Order {} settled and recorded as FILLED on recovery", order.getOrderId());
        return true;
    }
}
