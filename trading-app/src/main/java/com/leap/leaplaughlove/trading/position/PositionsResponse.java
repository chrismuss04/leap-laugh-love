package com.leap.leaplaughlove.trading.position;

import java.util.List;
import java.util.UUID;

public record PositionsResponse(
        UUID accountId,
        String accountNumber,
        String baseCurrency,
        List<PositionItem> positions
) {
}
