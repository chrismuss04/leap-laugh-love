package com.leap.leaplaughlove.trading.balance;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record BalanceResponse(
        List<AccountBalance> accounts,
        Map<String, BigDecimal> totalsByCurrency
) {}
