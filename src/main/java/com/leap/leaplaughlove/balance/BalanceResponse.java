package com.leap.leaplaughlove.balance;

import java.util.List;
import java.util.Map;

public record BalanceResponse(
        List<AccountBalance> accounts,
        Map<String, java.math.BigDecimal> totalsByCurrency
) {
}
