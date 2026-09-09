package com.leap.leaplaughlove.trading.balance;

import java.math.BigDecimal;
import java.util.UUID;

public record AccountBalance(
        UUID accountId,
        String accountNumber,
        String currency,
        BigDecimal balance
) {}
