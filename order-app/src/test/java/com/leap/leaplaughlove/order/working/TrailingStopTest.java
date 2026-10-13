package com.leap.leaplaughlove.order.working;

import com.leap.leaplaughlove.order.order.Order;
import com.leap.leaplaughlove.order.order.Order.OrderType;
import com.leap.leaplaughlove.order.order.Order.Side;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TrailingStop: a stop that follows the market in the order's favour")
class TrailingStopTest {

    private static BigDecimal d(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static Order trailing(Side side, String amount, String percent, String peak) {
        Order order = new Order(UUID.randomUUID(), null, side, 5L, OffsetDateTime.now());
        order.setConditions(OrderType.TRAILING_STOP, null, null, Order.TimeInForce.GTC, null);
        order.setTrailing(d(amount), d(percent));
        if (peak != null) {
            order.moveTrail(d(peak), TrailingStop.stopFor(side, d(peak), d(amount), d(percent)));
        }
        return order;
    }

    @Test
    @DisplayName("a sell's best price is its highest bid, a buy's its lowest ask")
    void bestPrice() {
        assertEquals(d("101"), TrailingStop.best(Side.SELL, d("100"), d("101")));
        assertEquals(d("100"), TrailingStop.best(Side.SELL, d("100"), d("99")));
        assertEquals(d("99"), TrailingStop.best(Side.BUY, d("100"), d("99")));
        assertEquals(d("100"), TrailingStop.best(Side.BUY, d("100"), d("101")));
        assertEquals(d("100"), TrailingStop.best(Side.SELL, null, d("100")));
    }

    @Test
    @DisplayName("the stop sits the trail below a sell's best price and above a buy's")
    void stopPrice() {
        assertEquals(d("95.0000"), TrailingStop.stopFor(Side.SELL, d("100"), d("5"), null));
        assertEquals(d("105.0000"), TrailingStop.stopFor(Side.BUY, d("100"), d("5"), null));
        assertEquals(d("98.0000"), TrailingStop.stopFor(Side.SELL, d("100"), null, d("2")));
        assertEquals(d("146.5100"), TrailingStop.stopFor(Side.SELL, d("149.50"), null, d("2")));
        assertEquals(d("103.0000"), TrailingStop.stopFor(Side.BUY, d("100"), null, d("3")));
    }

    @Test
    @DisplayName("a trail wider than the price puts a sell's stop at or below zero")
    void oversizedTrail() {
        assertTrue(TrailingStop.stopFor(Side.SELL, d("100"), d("100"), null).signum() <= 0);
        assertTrue(TrailingStop.stopFor(Side.SELL, d("100"), d("150"), null).signum() < 0);
    }

    @Test
    @DisplayName("a sell's stop rises behind a new high")
    void sellStopRises() {
        TrailingStop.Move move = TrailingStop.ratchet(trailing(Side.SELL, null, "2", "100"), d("110"));

        assertEquals(d("110"), move.best());
        assertEquals(d("107.8000"), move.stop());
    }

    @Test
    @DisplayName("a sell's stop stays put as the price falls or goes sideways")
    void sellStopNeverFalls() {
        Order order = trailing(Side.SELL, null, "2", "100");

        assertNull(TrailingStop.ratchet(order, d("99")));
        assertNull(TrailingStop.ratchet(order, d("100")));
    }

    @Test
    @DisplayName("a buy's stop falls behind a new low, and stays put as the price rises")
    void buyStopFalls() {
        Order order = trailing(Side.BUY, "5", null, "100");

        TrailingStop.Move move = TrailingStop.ratchet(order, d("90"));
        assertEquals(d("90"), move.best());
        assertEquals(d("95.0000"), move.stop());
        assertNull(TrailingStop.ratchet(order, d("104")));
    }

    @Test
    @DisplayName("a trailing order that has not started yet starts at the first price it sees")
    void startsAtTheFirstPrice() {
        TrailingStop.Move move = TrailingStop.ratchet(trailing(Side.SELL, "5", null, null), d("100"));

        assertEquals(d("100"), move.best());
        assertEquals(d("95.0000"), move.stop());
    }

    @Test
    @DisplayName("the evaluator fires a trailing sell once the bid falls to the stop it set")
    void firesThroughTheEvaluator() {
        Order order = trailing(Side.SELL, null, "2", "100");
        // Stop at 98.

        assertFalse(TriggerEvaluator.evaluate(order, d("98.01")).fills());
        TriggerEvaluator.Evaluation fired = TriggerEvaluator.evaluate(order, d("98"));
        assertTrue(fired.stopReached());
        assertEquals(d("98"), fired.fillPrice(), "fills at the market, like a stop");
    }
}
