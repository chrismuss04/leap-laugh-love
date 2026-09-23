package com.leap.leaplaughlove.trading.order;

import com.leap.leaplaughlove.trading.account.Account;
import com.leap.leaplaughlove.trading.ledger.CashLedgerEntry;
import com.leap.leaplaughlove.trading.ledger.CashLedgerRepository;
import com.leap.leaplaughlove.trading.position.Position;
import com.leap.leaplaughlove.trading.position.PositionMovement;
import com.leap.leaplaughlove.trading.position.PositionMovementRepository;
import com.leap.leaplaughlove.trading.position.PositionRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Writes everything a fill leaves behind: the FILLED execution, its cash settlement, its position
 * movement, and the updated holding. Shared by live order submission and {@link SeededFillService},
 * so a seeded fill is booked exactly the way a real one is.
 *
 * <p>Callers must invoke this inside a transaction; it does not open one.
 */
@Component
public class FillRecorder {

    private final ExecutionRepository executionRepository;
    private final CashLedgerRepository cashLedgerRepository;
    private final PositionMovementRepository positionMovementRepository;
    private final PositionRepository positionRepository;

    public FillRecorder(ExecutionRepository executionRepository,
                        CashLedgerRepository cashLedgerRepository,
                        PositionMovementRepository positionMovementRepository,
                        PositionRepository positionRepository) {
        this.executionRepository = executionRepository;
        this.cashLedgerRepository = cashLedgerRepository;
        this.positionMovementRepository = positionMovementRepository;
        this.positionRepository = positionRepository;
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
        propagateCashLedger(order, execution, price, time);
        propagatePositionLedgerAndHoldings(order, execution, price, time);
        return execution;
    }

    private void propagateCashLedger(Order order, Execution execution, BigDecimal price, OffsetDateTime time) {
        Account account = order.getAccount();
        long quantity = order.getQuantity();
        BigDecimal tradeAmount = price.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal ledgerAmount;
        String entryType;
        String description;

        if (order.getSide() == Order.Side.BUY) {
            ledgerAmount = tradeAmount.negate();
            entryType = "BUY_SETTLEMENT";
            description = "Buy settlement: " + order.getInstrument().getSymbol() + " " + quantity + " @ $" + price;
        } else {
            ledgerAmount = tradeAmount;
            entryType = "SELL_SETTLEMENT";
            description = "Sell settlement: " + order.getInstrument().getSymbol() + " " + quantity + " @ $" + price;
        }

        CashLedgerEntry entry = new CashLedgerEntry(
                account.getAccountId(), order.getOrderId(), execution.getExecutionId(),
                entryType, ledgerAmount, account.getBaseCurrency(), time, description);
        cashLedgerRepository.save(entry);
    }

    private void propagatePositionLedgerAndHoldings(Order order, Execution execution,
                                                    BigDecimal price, OffsetDateTime time) {
        Account account = order.getAccount();
        Instrument instrument = order.getInstrument();
        long quantity = order.getQuantity();
        BigDecimal tradeCost = price.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
        String movementType;
        long quantityDelta;
        BigDecimal costDelta;

        if (order.getSide() == Order.Side.BUY) {
            movementType = "BUY_FILL";
            quantityDelta = quantity;
            costDelta = tradeCost;
        } else {
            movementType = "SELL_FILL";
            quantityDelta = -quantity;
            costDelta = tradeCost.negate();
        }

        // Record in position movements ledger (audit trail)
        PositionMovement movement = new PositionMovement(
                account.getAccountId(), instrument.getInstrumentId(), order.getOrderId(),
                execution.getExecutionId(), movementType, quantityDelta, costDelta, time);
        positionMovementRepository.save(movement);

        // Update current position holdings (trading.positions); locked to prevent
        // concurrent executions on the same account/instrument from losing an update
        Optional<Position> existingOpt = positionRepository.findByIdForUpdate(
                account.getAccountId(), instrument.getInstrumentId());

        if (order.getSide() == Order.Side.BUY) {
            if (existingOpt.isEmpty()) {
                Position newPosition = new Position(
                        account.getAccountId(), instrument.getInstrumentId(), quantity, price, time);
                positionRepository.save(newPosition);
            } else {
                Position p = existingOpt.get();
                long oldQty = p.getQuantity();
                BigDecimal oldAvg = p.getAvgCost();
                long newQty = oldQty + quantity;
                BigDecimal totalCost = oldAvg.multiply(BigDecimal.valueOf(oldQty)).add(tradeCost);
                BigDecimal newAvgCost = totalCost.divide(BigDecimal.valueOf(newQty), 6, RoundingMode.HALF_UP);

                p.setQuantity(newQty);
                p.setAvgCost(newAvgCost);
                p.setUpdatedAt(time);
                positionRepository.save(p);
            }
        } else {
            if (existingOpt.isPresent()) {
                Position p = existingOpt.get();
                long newQty = Math.max(0L, p.getQuantity() - quantity);
                p.setQuantity(newQty);
                if (newQty == 0) {
                    p.setAvgCost(BigDecimal.ZERO);
                }
                p.setUpdatedAt(time);
                positionRepository.save(p);
            }
        }
    }
}
