package com.leap.leaplaughlove.account.position;

import java.util.List;
import java.util.UUID;

/**
 * Response DTO for representing the positions of an account.
 * @param accountId the unique identifier of the account
 * @param accountNumber the account number
 * @param baseCurrency the base currency of the account
 * @param positions the list of positions associated with the account
 */
public record PositionsResponse(
        UUID accountId,
        String accountNumber,
        String baseCurrency,
        List<PositionItem> positions
) {}

