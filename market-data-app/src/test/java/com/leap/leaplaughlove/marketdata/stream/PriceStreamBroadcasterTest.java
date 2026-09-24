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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PriceStreamBroadcaster Tests")
class PriceStreamBroadcasterTest {

    private final PriceStreamBroadcaster broadcaster = new PriceStreamBroadcaster();

    private static PriceTickEvent tick(String symbol, String price) {
        return new PriceTickEvent(new PriceState(symbol, new BigDecimal(price), OffsetDateTime.now()));
    }

    /** Records every price it is sent, optionally blocking each send until released. */
    private static class RecordingEmitter extends SseEmitter {
        final List<String> sent = new CopyOnWriteArrayList<>();
        final CountDownLatch release;
        final CountDownLatch firstSendStarted = new CountDownLatch(1);

        RecordingEmitter(boolean blocked) {
            super(0L);
            this.release = new CountDownLatch(blocked ? 1 : 0);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            firstSendStarted.countDown();
            try {
                release.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            String payload = builder.build().stream()
                    .map(part -> part.getData() instanceof PriceState state
                            ? state.symbol() + "=" + state.price().toPlainString() : "")
                    .reduce("", String::concat);
            sent.add(payload);
        }
    }

    private static void awaitSize(List<?> list, int size) throws InterruptedException {
        awaitUntil(() -> list.size() >= size);
    }

    private static void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    @Test
    @DisplayName("a client that stops reading never blocks the tick thread or other clients")
    void stuckClientDoesNotBlockTicks() throws Exception {
        RecordingEmitter stuck = new RecordingEmitter(true);
        RecordingEmitter healthy = new RecordingEmitter(false);
        broadcaster.register(stuck, Set.of());
        broadcaster.register(healthy, Set.of());

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            for (int i = 0; i < 100; i++) {
                broadcaster.onPriceTick(tick("AAPL", "460." + i));
            }
        });
        assertTrue(stuck.firstSendStarted.await(5, TimeUnit.SECONDS), "the stuck client's send should be in progress");

        // the healthy sender may still be draining earlier ticks, so wait for the last one to land
        awaitUntil(() -> healthy.sent.contains("AAPL=460.99"));
        assertTrue(healthy.sent.contains("AAPL=460.99"), "the healthy client should get the latest price: " + healthy.sent);
        stuck.release.countDown();
    }

    @Test
    @DisplayName("a slow client gets the latest price per symbol, not a backlog of every tick")
    void slowClientGetsLatestPricePerSymbol() throws Exception {
        RecordingEmitter slow = new RecordingEmitter(true);
        broadcaster.register(slow, Set.of());

        broadcaster.onPriceTick(tick("AAPL", "1"));
        assertTrue(slow.firstSendStarted.await(5, TimeUnit.SECONDS));
        for (int i = 2; i <= 50; i++) {
            broadcaster.onPriceTick(tick("AAPL", Integer.toString(i)));
            broadcaster.onPriceTick(tick("MSFT", Integer.toString(i)));
        }
        slow.release.countDown();

        awaitSize(slow.sent, 3);
        Thread.sleep(100);
        assertEquals(3, slow.sent.size(), "in-flight tick plus one latest price per symbol: " + slow.sent);
        assertEquals("AAPL=1", slow.sent.get(0));
        assertTrue(slow.sent.containsAll(List.of("AAPL=50", "MSFT=50")), slow.sent.toString());
    }

    @Test
    @DisplayName("only ticks for the subscriber's symbols are sent")
    void filtersBySymbol() throws Exception {
        RecordingEmitter emitter = new RecordingEmitter(false);
        broadcaster.register(emitter, Set.of("MSFT"));

        broadcaster.onPriceTick(tick("AAPL", "460"));
        broadcaster.onPriceTick(tick("MSFT", "305"));

        awaitSize(emitter.sent, 1);
        Thread.sleep(100);
        assertEquals(List.of("MSFT=305"), emitter.sent);
    }

    @Test
    @DisplayName("a client whose send fails is dropped")
    void failedClientIsDropped() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        SseEmitter broken = new SseEmitter(0L) {
            @Override
            public void send(SseEventBuilder builder) throws IOException {
                attempts.incrementAndGet();
                throw new IOException("Broken pipe");
            }
        };
        broadcaster.register(broken, Set.of());

        broadcaster.onPriceTick(tick("AAPL", "460"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (attempts.get() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        Thread.sleep(100);
        broadcaster.onPriceTick(tick("AAPL", "461"));
        Thread.sleep(100);

        assertEquals(1, attempts.get(), "no sends after the first failure");
    }
}
