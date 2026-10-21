package com.leap.leaplaughlove.account.position;

import java.util.List;
import java.util.UUID;

/**
 * Response DTO for representing the positions of an account.
 * @param accountId the unique identifier of the account
 * @param accountNumber the account number
 * @param baseCurrency the base currency of the account
 * @param positions the list of positions associated with the account
 * @param closedToday positions held earlier today and since sold down to nothing, with a quantity
 *     of zero, so their gain or loss today still counts toward the account's
 */
public record PositionsResponse(
        UUID accountId,
        String accountNumber,
        String baseCurrency,
        List<PositionItem> positions,
        List<PositionItem> closedToday
) {}

