package com.leap.leaplaughlove.order.working;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("OrderUpdateBroadcaster: order updates pushed to the client's open streams")
class OrderUpdateBroadcasterTest {

    private OrderUpdateBroadcaster broadcaster;
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // Sends on the calling thread, so what was sent can be checked at once.
        broadcaster = new OrderUpdateBroadcaster(Runnable::run);
    }

    private static OrderUpdate update() {
        return new OrderUpdate(UUID.randomUUID(), "AAPL", "BUY", 2, "LIMIT", "FILLED",
                new BigDecimal("144.90"), null, OffsetDateTime.now());
    }

    /** The event names and payloads sent, in order, as the wire would carry them. */
    private static List<String> sent(SseEmitter emitter) throws IOException {
        ArgumentCaptor<SseEmitter.SseEventBuilder> events = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, atLeast(0)).send(events.capture());
        return events.getAllValues().stream()
                .map(event -> event.build().stream().map(part -> String.valueOf(part.getData())).reduce("", String::concat))
                .toList();
    }

    @Test
    @DisplayName("tells a new stream it is up")
    void announcesItself() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        broadcaster.register(emitter, alice, () -> true);

        assertTrue(sent(emitter).get(0).contains("event:ready"));
    }

    @Test
    @DisplayName("sends an update only to the order's owner")
    void sendsToTheOwnerOnly() throws IOException {
        SseEmitter aliceStream = mock(SseEmitter.class);
        SseEmitter bobStream = mock(SseEmitter.class);
        broadcaster.register(aliceStream, alice, () -> true);
        broadcaster.register(bobStream, bob, () -> true);

        broadcaster.publish(alice, update());

        List<String> alices = sent(aliceStream);
        assertEquals(2, alices.size());
        assertTrue(alices.get(1).contains("event:order"));
        assertTrue(alices.get(1).contains("AAPL"));
        assertEquals(1, sent(bobStream).size(), "bob only got the ready event");
    }

    @Test
    @DisplayName("sends to every stream a client has open")
    void sendsToEveryTab() throws IOException {
        SseEmitter first = mock(SseEmitter.class);
        SseEmitter second = mock(SseEmitter.class);
        broadcaster.register(first, alice, () -> true);
        broadcaster.register(second, alice, () -> true);

        broadcaster.publish(alice, update());

        assertEquals(2, sent(first).size());
        assertEquals(2, sent(second).size());
    }

    @Test
    @DisplayName("drops a stream whose client has gone")
    void dropsAGoneClient() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IOException("broken pipe")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        broadcaster.register(emitter, alice, () -> true);
        assertEquals(0, broadcaster.openStreams());

        SseEmitter healthy = mock(SseEmitter.class);
        broadcaster.register(healthy, alice, () -> true);
        assertEquals(1, broadcaster.openStreams());
    }

    @Test
    @DisplayName("a heartbeat keeps a live stream open and ends one whose login has expired")
    void heartbeatChecksTheSession() throws IOException {
        SseEmitter live = mock(SseEmitter.class);
        SseEmitter expired = mock(SseEmitter.class);
        broadcaster.register(live, alice, () -> true);
        broadcaster.register(expired, bob, () -> false);

        broadcaster.heartbeat();

        assertTrue(sent(live).get(1).contains("event:ping"));
        verify(expired).complete();
        assertEquals(1, broadcaster.openStreams());
    }

    @Test
    @DisplayName("a failing session check ends the stream rather than leaving it open")
    void sessionStoreFailureClosesTheStream() {
        SseEmitter emitter = mock(SseEmitter.class);
        broadcaster.register(emitter, alice, () -> {
            throw new IllegalStateException("database down");
        });

        broadcaster.heartbeat();

        verify(emitter).complete();
        assertEquals(0, broadcaster.openStreams());
    }
}
