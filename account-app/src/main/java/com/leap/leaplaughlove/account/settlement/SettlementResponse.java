package com.leap.leaplaughlove.account.settlement;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Data Transfer Object for settlement response information.
 * @param cashLedgerId the unique identifier of the cash ledger entry
 * @param balanceAfter the cash balance after the settlement
 * @param positionQuantity the quantity of the instrument held after the settlement
 * @param positionAvgCost the average cost of the instrument position after the settlement
 */
public record SettlementResponse(
        UUID cashLedgerId,
        BigDecimal balanceAfter,
        long positionQuantity,
        BigDecimal positionAvgCost
) {}

