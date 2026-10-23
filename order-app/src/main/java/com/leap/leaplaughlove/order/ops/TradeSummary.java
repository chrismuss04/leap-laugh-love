package com.leap.leaplaughlove.order.ops;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A trade as a search result.
 * @param orderId the order's ID
 * @param submittedAt when the order was submitted
 * @param clientEmail the client's email
 * @param accountNumber the account's number
 * @param symbol the instrument's symbol
 * @param side BUY or SELL
 * @param quantity the number of shares ordered
 * @param status the order's status
 */
public record TradeSummary(
        UUID orderId,
        OffsetDateTime submittedAt,
        String clientEmail,
        String accountNumber,
        String symbol,
        String side,
        long quantity,
        String status
) {
}
