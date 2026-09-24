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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;

/**
 * Writes everything a fill leaves behind: the FILLED execution, its position movement in the audit ledger,
 * and delegates cash settlement & position updates to account-app via AccountClient. Shared by live
 * order submission and SeededFillService, so a seeded fill is booked exactly the way a real one is.
 *
 * <p>Callers must invoke this inside a transaction; it does not open one.
 */
@Component
public class FillRecorder {

    private final ExecutionRepository executionRepository;
    private final PositionMovementRepository positionMovementRepository;
    private final AccountClient accountClient;
    private final ThreadLocal<BigDecimal> lastBalanceAfter = new ThreadLocal<>();

    public FillRecorder(ExecutionRepository executionRepository,
                        PositionMovementRepository positionMovementRepository,
                        AccountClient accountClient) {
        this.executionRepository = executionRepository;
        this.positionMovementRepository = positionMovementRepository;
        this.accountClient = accountClient;
    }

    /**
     * Books a fill of the whole order at one price. Does not change the order's own status.
     * @param order the order being filled
     * @param price the execution price per share
     * @param time when the fill happened; stamped on every row written
     * @return the saved FILLED execution
     */
    public Execution recordFill(Order order, BigDecimal price, OffsetDateTime time) {
        Execution execution = executionRepository.saveAndFlush(new Execution(
                order, order.getQuantity(), price, Execution.Status.FILLED,
                "Executed at market price", time));
        propagatePositionMovement(order, execution, price, time);
        propagateSettlement(order, execution, price, time);
        return execution;
    }

    private void propagatePositionMovement(Order order, Execution execution, BigDecimal price, OffsetDateTime time) {
        long quantity = order.getQuantity();
        BigDecimal tradeCost = price.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
        String movementType = order.getSide() == Order.Side.BUY ? "BUY_FILL" : "SELL_FILL";
        long qtyDelta = order.getSide() == Order.Side.BUY ? quantity : -quantity;
        BigDecimal costDelta = order.getSide() == Order.Side.BUY ? tradeCost : tradeCost.negate();

        PositionMovement movement = new PositionMovement(
                order.getAccountId(), order.getInstrument().getInstrumentId(), order.getOrderId(),
                execution.getExecutionId(), movementType, qtyDelta, costDelta, time);
        positionMovementRepository.save(movement);
    }

    private void propagateSettlement(Order order, Execution execution, BigDecimal price, OffsetDateTime time) {
        SettlementRequest settlementRequest = new SettlementRequest(
                order.getOrderId(),
                execution.getExecutionId(),
                order.getInstrument().getInstrumentId(),
                order.getInstrument().getSymbol(),
                order.getSide().name(),
                order.getQuantity(),
                price,
                time
        );
        SettlementResponse settlementResponse = accountClient.settleOrder(order.getAccountId(), settlementRequest);
        if (settlementResponse != null) {
            lastBalanceAfter.set(settlementResponse.balanceAfter());
        }
    }

    /**
     * Returns the balanceAfter returned from the last settlement call on the current thread.
     * @return the last balanceAfter
     */
    public BigDecimal getLastBalanceAfter() {
        return lastBalanceAfter.get();
    }
}
