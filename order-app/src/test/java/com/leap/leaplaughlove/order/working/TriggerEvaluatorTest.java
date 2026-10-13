package com.leap.leaplaughlove.order.working;

import com.leap.leaplaughlove.order.order.Order;
import com.leap.leaplaughlove.order.order.Order.OrderType;
import com.leap.leaplaughlove.order.order.Order.Side;
import com.leap.leaplaughlove.order.quote.QuoteSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TriggerEvaluator: when an open order's price is reached")
class TriggerEvaluatorTest {

    private static BigDecimal price(String value) {
        return value == null || value.isBlank() ? null : new BigDecimal(value);
    }

    private static QuoteSnapshot quote(String bid, String ask, String last) {
        return new QuoteSnapshot("AAPL", price(bid), 100L, price(ask), 100L, price(last), 100L, "NASDAQ",
                OffsetDateTime.now());
    }

    @Test
    @DisplayName("a buy is priced at the ask and a sell at the bid")
    void executablePriceUsesTheSideOfTheQuote() {
        QuoteSnapshot quote = quote("149.50", "150.00", "149.75");

        assertEquals(new BigDecimal("150.0000"), TriggerEvaluator.executablePrice(Side.BUY, quote));
        assertEquals(new BigDecimal("149.5000"), TriggerEvaluator.executablePrice(Side.SELL, quote));
    }

    @Test
    @DisplayName("falls back to the last trade when that side of the quote is missing, and to null with no price")
    void executablePriceFallsBackToTheLastTrade() {
        assertEquals(new BigDecimal("149.7500"),
                TriggerEvaluator.executablePrice(Side.BUY, quote("149.50", "0", "149.75")));
        assertNull(TriggerEvaluator.executablePrice(Side.SELL, quote(null, null, null)));
        assertNull(TriggerEvaluator.executablePrice(Side.SELL, null));
    }

    @ParameterizedTest(name = "{0} {1} limit {2} at {3} fills: {4}")
    @CsvSource({
            "LIMIT, BUY,  145.00, 144.99, true",
            "LIMIT, BUY,  145.00, 145.00, true",
            "LIMIT, BUY,  145.00, 145.01, false",
            "LIMIT, SELL, 155.00, 155.01, true",
            "LIMIT, SELL, 155.00, 155.00, true",
            "LIMIT, SELL, 155.00, 154.99, false",
    })
    @DisplayName("a limit fills at its price or better, and only then")
    void limitOrders(OrderType type, Side side, String limit, String market, boolean fills) {
        TriggerEvaluator.Evaluation evaluation = TriggerEvaluator.evaluate(
                type, side, price(limit), null, false, price(market));

        assertEquals(fills, evaluation.fills());
        assertFalse(evaluation.stopReached());
        if (fills) {
            assertEquals(price(market), evaluation.fillPrice(), "fills at the market, the limit or better");
        }
    }

    @ParameterizedTest(name = "{0} stop {1} at {2} triggers: {3}")
    @CsvSource({
            "SELL, 140.00, 140.01, false",
            "SELL, 140.00, 140.00, true",
            "SELL, 140.00, 139.00, true",
            "BUY,  160.00, 159.99, false",
            "BUY,  160.00, 160.00, true",
            "BUY,  160.00, 161.00, true",
    })
    @DisplayName("a stop triggers when the price reaches it, then fills at the market")
    void stopOrders(Side side, String stop, String market, boolean triggers) {
        TriggerEvaluator.Evaluation evaluation = TriggerEvaluator.evaluate(
                OrderType.STOP, side, null, price(stop), false, price(market));

        assertEquals(triggers, evaluation.stopReached());
        assertEquals(triggers ? price(market) : null, evaluation.fillPrice());
    }

    @Test
    @DisplayName("a stop-limit triggered at its stop fills only if the price is also within its limit")
    void stopLimitTriggersThenWorksAsALimit() {
        // Sell stop 140, limit 139: the bid gapped through both to 138.50.
        TriggerEvaluator.Evaluation gapped = TriggerEvaluator.evaluate(
                OrderType.STOP_LIMIT, Side.SELL, price("139.00"), price("140.00"), false, price("138.50"));
        assertTrue(gapped.stopReached());
        assertFalse(gapped.fills(), "below its limit, so it waits");

        TriggerEvaluator.Evaluation within = TriggerEvaluator.evaluate(
                OrderType.STOP_LIMIT, Side.SELL, price("139.00"), price("140.00"), false, price("139.50"));
        assertTrue(within.stopReached());
        assertEquals(price("139.50"), within.fillPrice());
    }

    @Test
    @DisplayName("a triggered stop-limit stays triggered when the price moves back above its stop")
    void triggeredStopLimitStaysTriggered() {
        TriggerEvaluator.Evaluation evaluation = TriggerEvaluator.evaluate(
                OrderType.STOP_LIMIT, Side.SELL, price("139.00"), price("140.00"), true, price("141.00"));

        assertTrue(evaluation.stopReached());
        assertEquals(price("141.00"), evaluation.fillPrice(), "above its limit, so it fills");
    }

    @Test
    @DisplayName("with no price there is nothing to act on")
    void noPriceNeverFills() {
        TriggerEvaluator.Evaluation evaluation = TriggerEvaluator.evaluate(
                OrderType.STOP, Side.SELL, null, price("140.00"), true, null);

        assertFalse(evaluation.fills());
        assertFalse(evaluation.stopReached());
    }

    @Test
    @DisplayName("a market order always fills at the price")
    void marketOrdersFill() {
        assertEquals(price("150.00"), TriggerEvaluator.evaluate(
                OrderType.MARKET, Side.BUY, null, null, false, price("150.00")).fillPrice());
    }

    @Test
    @DisplayName("reads a stored order's conditions, including an earlier trigger")
    void evaluatesAStoredOrder() {
        Order order = new Order(java.util.UUID.randomUUID(), null, Side.SELL, 5L, OffsetDateTime.now());
        order.setConditions(OrderType.STOP_LIMIT, price("139.00"), price("140.00"), Order.TimeInForce.GTC, null);
        order.markTriggered(OffsetDateTime.now());

        assertTrue(TriggerEvaluator.evaluate(order, price("141.00")).fills());
    }
}
