package com.leap.leaplaughlove.marketdata.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * A symbol's price at the previous trading day's regular close, which today's change is measured
 * from.
 * @param symbol the instrument symbol
 * @param close the last price at or before that close
 * @param at when that close was: the previous trading day's regular close
 */
public record PreviousCloseResponse(String symbol, BigDecimal close, OffsetDateTime at) {
}
