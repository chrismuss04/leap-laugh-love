package com.leap.leaplaughlove.trading.position;

import java.util.List;

/**
 * Holdings for every active account belonging to the authenticated client, one
 * {@link PositionsResponse} per account (a client can hold more than one active account).
 */
public record ClientPositionsResponse(
        List<PositionsResponse> accounts
) {
}
