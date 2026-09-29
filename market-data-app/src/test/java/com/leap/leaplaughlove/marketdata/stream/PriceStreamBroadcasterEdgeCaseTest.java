package com.leap.leaplaughlove.marketdata.stream;

import com.leap.leaplaughlove.marketdata.simulation.PriceState;
import com.leap.leaplaughlove.marketdata.simulation.PriceTickEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PriceStreamBroadcaster Edge Case & Robustness Tests")
class PriceStreamBroadcasterEdgeCaseTest {

    // Session Timeout & Revocation: an open stream cannot continue after session invalidation.
    @Test
    void stopsSendingWhenSessionIsRevoked() {
        var broadcaster = new PriceStreamBroadcaster(Runnable::run);
        var emitter = new CollectingEmitter();
        var active = new AtomicBoolean(true);
        broadcaster.register(emitter, Set.of(), active::get);
        broadcaster.onPriceTick(tick("AAPL", "100"));
        assertEquals(1, emitter.receivedPayloads.size());
        active.set(false);
        broadcaster.onPriceTick(tick("AAPL", "101"));
        broadcaster.onPriceTick(tick("AAPL", "102"));
        assertEquals(1, emitter.receivedPayloads.size());
    }

    @Test
    void stopsSendingWhenSessionStoreIsUnavailable() {
        var broadcaster = new PriceStreamBroadcaster(Runnable::run);
        var emitter = new CollectingEmitter();
        broadcaster.register(emitter, Set.of(), () -> {
            throw new org.springframework.dao.DataAccessResourceFailureException("offline");
        });
        broadcaster.onPriceTick(tick("AAPL", "100"));
        assertTrue(emitter.receivedPayloads.isEmpty());
    }

    private static PriceTickEvent tick(String symbol, String price) {
        return new PriceTickEvent(new PriceState(symbol, new BigDecimal(price), OffsetDateTime.now()));
    }

    private static void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    /** Emitter that collects sent symbols. */
    private static class CollectingEmitter extends SseEmitter {
        final List<String> receivedPayloads = new CopyOnWriteArrayList<>();

        CollectingEmitter() {
            super(0L);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            String payload = builder.build().stream()
                    .map(part -> part.getData() instanceof PriceState state
                            ? state.symbol() + "=" + state.price().toPlainString() : "")
                    .reduce("", String::concat);
            receivedPayloads.add(payload);
        }
    }

    @Test
    @DisplayName("client unregister during active high-volume tick burst does not deadlock or crash broadcaster")
    void testClientUnregisterDuringBurst() throws Exception {
        PriceStreamBroadcaster broadcaster = new PriceStreamBroadcaster();
        CollectingEmitter volatileClient = new CollectingEmitter();
        CollectingEmitter stableClient = new CollectingEmitter();

        broadcaster.register(volatileClient, Set.of("AAPL"));
        broadcaster.register(stableClient, Set.of("AAPL"));

        ExecutorService burstExecutor = Executors.newFixedThreadPool(2);
        CountDownLatch burstStarted = new CountDownLatch(1);

        // Concurrently fire ticks
        burstExecutor.submit(() -> {
            burstStarted.countDown();
            for (int i = 0; i < 200; i++) {
                broadcaster.onPriceTick(tick("AAPL", "150." + i));
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        assertTrue(burstStarted.await(2, TimeUnit.SECONDS));
        Thread.sleep(20);

        // Unregister volatile client mid-burst via completion callback
        volatileClient.complete();

        // The burst should complete without throwing or hanging
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            burstExecutor.shutdown();
            assertTrue(burstExecutor.awaitTermination(4, TimeUnit.SECONDS));
        });

        // Stable client receives ticks up to latest
        awaitUntil(() -> stableClient.receivedPayloads.stream().anyMatch(p -> p.startsWith("AAPL=150.")));
        assertTrue(stableClient.receivedPayloads.stream().anyMatch(p -> p.startsWith("AAPL=150.")));

        // Verify broadcaster is still fully operational with a fresh subscriber
        CollectingEmitter newClient = new CollectingEmitter();
        broadcaster.register(newClient, Set.of("AAPL"));
        broadcaster.onPriceTick(tick("AAPL", "160.00"));
        awaitUntil(() -> newClient.receivedPayloads.contains("AAPL=160.00"));
        assertTrue(newClient.receivedPayloads.contains("AAPL=160.00"));
    }

    @Test
    @DisplayName("client filtering receives only subscribed symbols")
    void testFilteringSpecificSymbolSets() throws Exception {
        PriceStreamBroadcaster broadcaster = new PriceStreamBroadcaster();
        CollectingEmitter client = new CollectingEmitter();
        broadcaster.register(client, Set.of("AAPL", "GOOG"));

        broadcaster.onPriceTick(tick("AAPL", "180.00"));
        broadcaster.onPriceTick(tick("MSFT", "420.00"));
        broadcaster.onPriceTick(tick("GOOG", "175.50"));
        broadcaster.onPriceTick(tick("TSLA", "250.00"));

        awaitUntil(() -> client.receivedPayloads.size() >= 2);
        Thread.sleep(100);

        assertTrue(client.receivedPayloads.contains("AAPL=180.00"));
        assertTrue(client.receivedPayloads.contains("GOOG=175.50"));
        assertFalse(client.receivedPayloads.stream().anyMatch(p -> p.startsWith("MSFT=")));
        assertFalse(client.receivedPayloads.stream().anyMatch(p -> p.startsWith("TSLA=")));
    }

    @Test
    @DisplayName("wildcard empty symbol filter receives all ticks")
    void testWildcardFilterReceivesAllTicks() throws Exception {
        PriceStreamBroadcaster broadcaster = new PriceStreamBroadcaster();
        CollectingEmitter client = new CollectingEmitter();
        broadcaster.register(client, Set.of());

        broadcaster.onPriceTick(tick("AAPL", "180.00"));
        broadcaster.onPriceTick(tick("MSFT", "420.00"));
        broadcaster.onPriceTick(tick("NVDA", "125.00"));

        awaitUntil(() -> client.receivedPayloads.size() >= 3);
        Thread.sleep(100);

        assertTrue(client.receivedPayloads.contains("AAPL=180.00"));
        assertTrue(client.receivedPayloads.contains("MSFT=420.00"));
        assertTrue(client.receivedPayloads.contains("NVDA=125.00"));
    }

    @Test
    @DisplayName("handling client IllegalStateException drops subscriber without crashing broadcaster")
    void testClientThrowsIllegalStateException() throws Exception {
        PriceStreamBroadcaster broadcaster = new PriceStreamBroadcaster();
        AtomicInteger attempts = new AtomicInteger();

        SseEmitter brokenEmitter = new SseEmitter(0L) {
            @Override
            public void send(SseEventBuilder builder) {
                attempts.incrementAndGet();
                throw new IllegalStateException("Broken SSE pipe / connection closed");
            }
        };

        broadcaster.register(brokenEmitter, Set.of("AAPL"));
        broadcaster.onPriceTick(tick("AAPL", "150.00"));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (attempts.get() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        Thread.sleep(100);

        // Broadcaster should have dropped the broken subscriber; further ticks must not invoke it
        broadcaster.onPriceTick(tick("AAPL", "151.00"));
        broadcaster.onPriceTick(tick("AAPL", "152.00"));
        Thread.sleep(100);

        assertEquals(1, attempts.get(), "broken subscriber must be removed after first failure");
    }

    @Test
    @DisplayName("subscribe factory method creates SseEmitter with 0L timeout")
    void testSubscribeFactoryMethod() {
        PriceStreamBroadcaster broadcaster = new PriceStreamBroadcaster();
        SseEmitter emitter = broadcaster.subscribe(Set.of("AAPL"));

        assertNotNull(emitter);
        assertEquals(0L, emitter.getTimeout());
    }

    @Test
    @DisplayName("custom executor constructor executes drain tasks on provided executor")
    void testCustomExecutor() throws Exception {
        AtomicBoolean customExecutorUsed = new AtomicBoolean(false);
        PriceStreamBroadcaster broadcaster = new PriceStreamBroadcaster(command -> {
            customExecutorUsed.set(true);
            command.run();
        });

        CollectingEmitter client = new CollectingEmitter();
        broadcaster.register(client, Set.of("AAPL"));
        broadcaster.onPriceTick(tick("AAPL", "190.00"));

        awaitUntil(customExecutorUsed::get);
        assertTrue(customExecutorUsed.get(), "task should run on custom executor");
        awaitUntil(() -> client.receivedPayloads.contains("AAPL=190.00"));
        assertTrue(client.receivedPayloads.contains("AAPL=190.00"));
    }
}

