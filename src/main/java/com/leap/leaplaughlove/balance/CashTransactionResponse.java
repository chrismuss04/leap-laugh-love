package com.leap.leaplaughlove.balance;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record CashTransactionResponse(
        UUID cashLedgerId,
        UUID accountId,
        String entryType,
        BigDecimal amount,
        String currency,
        BigDecimal balanceAfter,
        OffsetDateTime createdAt,
        String description) {
}
