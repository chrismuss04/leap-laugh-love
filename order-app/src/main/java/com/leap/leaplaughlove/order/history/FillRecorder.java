package com.leap.leaplaughlove.order.history;

import com.leap.leaplaughlove.order.client.AccountClient;
import com.leap.leaplaughlove.order.client.SettlementRequest;
import com.leap.leaplaughlove.order.client.SettlementResponse;
import com.leap.leaplaughlove.order.execution.Execution;
import com.leap.leaplaughlove.order.execution.ExecutionRepository;
import com.leap.leaplaughlove.order.order.Order;
import com.leap.leaplaughlove.order.position.PositionMovement;
import com.leap.leaplaughlove.order.position.PositionMovementRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Writes everything a fill leaves behind: the FILLED execution, the cash settlement and holding
 * update (delegated to account-app via AccountClient), and its position movement in the audit
 * ledger. Shared by live order submission and SeededFillService, so a seeded fill is booked
 * exactly the way a real one is.
 *
 * <p>A fill is booked in three steps, and callers must commit between them:
 * <ol>
 *   <li>{@link #recordExecution} inside a transaction, which must commit before settling:
 *       account-app writes the settlement on its own database connection, and its ledger rows
 *       reference the order and the execution by foreign key, so it can't see either while this
 *       app's transaction is still open.</li>
 *   <li>{@link #settle} outside any transaction.</li>
 *   <li>{@link #recordPositionMovement} inside a transaction, only once settling succeeded:
 *       account-app builds portfolio history from position movements, so one must never exist
 *       for a fill that didn't settle.</li>
 * </ol>
 */
@Component
public class FillRecorder {

    private final ExecutionRepository executionRepository;
    private final PositionMovementRepository positionMovementRepository;
    private final AccountClient accountClient;

    public FillRecorder(ExecutionRepository executionRepository,
                        PositionMovementRepository positionMovementRepository,
                        AccountClient accountClient) {
        this.executionRepository = executionRepository;
        this.positionMovementRepository = positionMovementRepository;
        this.accountClient = accountClient;
    }

    /**
     * Writes the FILLED execution for a fill of the whole order at one price. Does not change the
     * order's own status.
     * @param order the order being filled
     * @param price the execution price per share
     * @param time when the fill happened; stamped on every row the fill writes
     * @return the saved FILLED execution
     */
    public Execution recordExecution(Order order, BigDecimal price, OffsetDateTime time) {
        return executionRepository.saveAndFlush(new Execution(
                order, order.getQuantity(), price, Execution.Status.FILLED,
                "Executed at market price", time));
    }

    /**
     * Settles a committed execution with account-app: posts the cash and updates the holding.
     * Safe to repeat - account-app settles each execution once and answers a repeat from the
     * state it already booked.
     * @param order the filled order
     * @param execution its committed FILLED execution
     * @param bearerToken the token to call account-app with, or null to forward the current
     *                    request's own
     * @return account-app's settlement result
     */
    public SettlementResponse settle(Order order, Execution execution, String bearerToken) {
        SettlementRequest request = new SettlementRequest(
                order.getOrderId(),
                execution.getExecutionId(),
                order.getInstrument().getInstrumentId(),
                order.getInstrument().getSymbol(),
                order.getSide().name(),
                order.getQuantity(),
                execution.getFillPrice(),
                execution.getExecutedAt()
        );
        return bearerToken == null
                ? accountClient.settleOrder(order.getAccountId(), request)
                : accountClient.settleOrderAs(order.getAccountId(), request, bearerToken);
    }

    /**
     * Tells whether a failed {@link #settle} means account-app refused the fill, so nothing was
     * booked. account-app settles in one transaction and answers a refusal (e.g. not enough
     * shares to sell) with a 4xx. Anything else - no answer (unreachable, timed out), a 5xx, or a
     * failure on this side - leaves it unknown whether cash and holdings moved. Such a fill must
     * not be recorded as rejected: it is left for {@link PendingFillRecovery} to settle again,
     * which is safe because account-app settles each execution only once.
     * @param failure what {@link #settle} threw
     * @return true only if account-app answered with a refusal
     */
    public static boolean isRefused(RuntimeException failure) {
        return failure instanceof HttpClientErrorException;
    }

    /**
     * account-app's own explanation of a refused settlement, e.g. "Cannot settle sell:
     * insufficient position quantity", read from the {@code message} of its error body.
     * @param refusal what {@link #settle} threw, for which {@link #isRefused} is true
     * @return the reason, or the exception's message if the body has none
     */
    public static String refusalReason(RuntimeException refusal) {
        if (refusal instanceof HttpClientErrorException clientError) {
            try {
                Map<?, ?> body = clientError.getResponseBodyAs(Map.class);
                if (body != null && body.get("message") instanceof String message && !message.isBlank()) {
                    return message;
                }
            } catch (RuntimeException unreadable) {
                // Not account-app's JSON error body; fall back to the exception's message.
            }
        }
        return refusal.getMessage();
    }

    /**
     * Finishes booking a settled live fill: writes its position movement and marks the order
     * FILLED. Call inside a transaction holding the order's row lock (see
     * OrderRepository#findByIdForUpdate), so live submission and {@link PendingFillRecovery}
     * can't both finish it. Does nothing if the fill was already finished.
     * @param lockedOrder the order, loaded with its row lock in the current transaction
     * @param execution its settled FILLED execution
     * @return true if this call finished the fill, false if it was already finished
     */
    public boolean completeFill(Order lockedOrder, Execution execution) {
        if (positionMovementRepository.existsByOrderId(lockedOrder.getOrderId())) {
            return false;
        }
        recordPositionMovement(lockedOrder, execution);
        lockedOrder.markFilled(execution.getExecutedAt());
        return true;
    }

    /**
     * Writes a settled fill's movement to the position movements ledger.
     * @param order the filled order
     * @param execution its settled FILLED execution
     */
    public void recordPositionMovement(Order order, Execution execution) {
        long quantity = order.getQuantity();
        BigDecimal tradeCost = execution.getFillPrice().multiply(BigDecimal.valueOf(quantity))
                .setScale(2, RoundingMode.HALF_UP);
        boolean buy = order.getSide() == Order.Side.BUY;

        positionMovementRepository.save(new PositionMovement(
                order.getAccountId(), order.getInstrument().getInstrumentId(), order.getOrderId(),
                execution.getExecutionId(), buy ? "BUY_FILL" : "SELL_FILL",
                buy ? quantity : -quantity, buy ? tradeCost : tradeCost.negate(),
                execution.getExecutedAt()));
    }
}
