package com.leap.leaplaughlove.order.client;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Data transfer object representing the response of a settlement operation.
 * 
 * @param cashLedgerId the unique identifier of the cash ledger
 * @param balanceAfter the balance after the settlement
 * @param positionQuantity the quantity of the position after the settlement
 * @param positionAvgCost the average cost of the position after the settlement
 */
public record SettlementResponse(
        UUID cashLedgerId,
        BigDecimal balanceAfter,
        long positionQuantity,
        BigDecimal positionAvgCost
) {}

