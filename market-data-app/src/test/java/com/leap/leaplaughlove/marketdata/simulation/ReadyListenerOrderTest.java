package com.leap.leaplaughlove.marketdata.simulation;

import com.leap.leaplaughlove.marketdata.history.PriceCandleAccumulator;
import com.leap.leaplaughlove.marketdata.ingestion.QuoteIngestionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.annotation.Order;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scheduled tick is already running when the app becomes ready, so the engine seeding its
 * state on ApplicationReadyEvent is what starts ticks flowing. Any tick consumer that loads its
 * instrument lookup on the same event must run first, or it rejects every tick until it has -
 * which on some machines flooded the log with "unknown or inactive instrument" after backfill.
 */
@DisplayName("ApplicationReadyEvent listener order")
class ReadyListenerOrderTest {

    @ParameterizedTest
    @ValueSource(classes = {QuoteIngestionService.class, PriceCandleAccumulator.class})
    @DisplayName("tick consumers load their instruments before the engine starts ticking")
    void consumersInitializeBeforeEngine(Class<?> consumer) throws Exception {
        assertTrue(order(consumer) < order(MarketSimulationEngine.class),
                consumer.getSimpleName() + ".initialize() must run before MarketSimulationEngine.initialize()");
    }

    private static int order(Class<?> type) throws Exception {
        Order order = type.getMethod("initialize").getAnnotation(Order.class);
        assertNotNull(order, type.getSimpleName() + ".initialize() needs an explicit @Order");
        return order.value();
    }
}
