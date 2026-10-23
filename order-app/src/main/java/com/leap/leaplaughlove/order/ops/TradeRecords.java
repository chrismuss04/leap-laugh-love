package com.leap.leaplaughlove.order.ops;

import com.leap.leaplaughlove.order.ops.TradeOpsRepository.CashEntryRecord;
import com.leap.leaplaughlove.order.ops.TradeOpsRepository.ExecutionRecord;
import com.leap.leaplaughlove.order.ops.TradeOpsRepository.OrderRecord;
import com.leap.leaplaughlove.order.ops.TradeOpsRepository.PositionMovementRecord;

import java.util.List;

/**
 * One order's audit records as stored: the order, then its executions, cash ledger entries and
 * position movements, each oldest first.
 * @param order the order
 * @param executions its execution attempts
 * @param cashEntries the cash ledger entries made for it
 * @param positionMovements the changes to holdings made for it
 */
public record TradeRecords(
        OrderRecord order,
        List<ExecutionRecord> executions,
        List<CashEntryRecord> cashEntries,
        List<PositionMovementRecord> positionMovements
) {
    /**
     * Copies the lists, treating null as empty.
     * @param order the order
     * @param executions its execution attempts, or null for none
     * @param cashEntries the cash ledger entries made for it, or null for none
     * @param positionMovements the changes to holdings made for it, or null for none
     */
    public TradeRecords {
        executions = executions != null ? List.copyOf(executions) : List.of();
        cashEntries = cashEntries != null ? List.copyOf(cashEntries) : List.of();
        positionMovements = positionMovements != null ? List.copyOf(positionMovements) : List.of();
    }
}
