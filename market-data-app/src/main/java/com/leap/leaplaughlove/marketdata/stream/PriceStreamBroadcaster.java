package com.leap.leaplaughlove.marketdata.stream;

import com.leap.leaplaughlove.marketdata.simulation.PriceState;
import com.leap.leaplaughlove.marketdata.simulation.PriceTickEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Broadcasts live simulation ticks to subscribed clients over Server-Sent
 * Events, optionally
 * filtered to a subset of symbols per subscriber.
 *
 * <p>
 * Ticks arrive on the simulation's tick thread, and nothing here may block it:
 * an SSE write
 * blocks for as long as the client isn't reading (a stalled tab, a dropped
 * connection the proxy
 * hasn't noticed yet - up to the socket's write timeout), and writing inline
 * froze the whole
 * market for every instrument and every other subscriber while it waited. So
 * each subscriber
 * gets its own sender on a virtual thread, and the tick thread only hands it
 * the price. What a
 * subscriber hasn't been sent yet is conflated to the latest price per symbol,
 * which is all a
 * live view needs and bounds a slow client's backlog to one entry per symbol it
 * watches.
 *
 * <p>
 * Sending never touches the database. Whether each subscriber's login session is still live is
 * checked for all of them at once by {@link PriceStreamSessionSweep}, which closes the streams
 * whose session has ended.
 */
@Component
public class PriceStreamBroadcaster {

    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final Executor sender;

    /**
     * Creates a broadcaster that sends to each subscriber on virtual threads.
     */
    public PriceStreamBroadcaster() {
        this(Executors.newVirtualThreadPerTaskExecutor());
    }

    /**
     * Creates a broadcaster that sends to subs using a given Executor.
     * 
     * @param sender the executor that will do the sending on virtual threads
     */
    PriceStreamBroadcaster(Executor sender) {
        this.sender = sender;
    }

    /**
     * Registers a new SSE subscriber, optionally filtered to a set of symbols.
     * 
     * @param symbolFilter the symbols to send to this subscriber, or empty to send
     *                     all
     * @return the emitter the subscriber should be returned to the client
     */
    public SseEmitter subscribe(Set<String> symbolFilter) {
        return register(new SseEmitter(0L), symbolFilter);
    }

    /**
     * Registers a new SSE subscriber that is dropped once its login session ends, as found by the
     * next session sweep.
     *
     * @param symbolFilter the symbols to send to this subscriber, or empty to send all
     * @param session      the subscriber's login session
     * @return the emitter to return to the client
     */
    public SseEmitter subscribe(Set<String> symbolFilter, StreamSession session) {
        return register(new SseEmitter(0L), symbolFilter, session);
    }

    /**
     * Registers a new SSE subscriber, optionally filtered to a set of symbols.
     * 
     * @param emitter      the emitter the subscriber should be returned to the
     *                     client
     * @param symbolFilter the symbols to send to this subscriber, or empty to send
     *                     all
     * @return the emitter the subscriber should be returned to the client
     */
    SseEmitter register(SseEmitter emitter, Set<String> symbolFilter) {
        return register(emitter, symbolFilter, null);
    }

    SseEmitter register(SseEmitter emitter, Set<String> symbolFilter, StreamSession session) {
        Subscription subscription = new Subscription(emitter, symbolFilter, session);
        subscriptions.add(subscription);
        emitter.onCompletion(subscription::close);
        emitter.onTimeout(subscription::close);
        emitter.onError(ex -> subscription.close());
        return emitter;
    }

    /**
     * Queues a simulation tick for every subscriber whose symbol filter matches it.
     * Never
     * blocks on a subscriber: the sends happen on each subscriber's own sender.
     * 
     * @param event the simulation tick event to broadcast
     */
    @EventListener
    public void onPriceTick(PriceTickEvent event) {
        PriceState state = event.priceState();
        for (Subscription subscription : subscriptions) {
            if (subscription.matches(state.symbol())) {
                subscription.offer(state);
            }
        }
    }

    /**
     * The login sessions of every open stream that has one.
     *
     * @return each open stream's session, once however many streams share it
     */
    public Set<StreamSession> openSessions() {
        return subscriptions.stream()
                .map(subscription -> subscription.session)
                .filter(session -> session != null)
                .collect(Collectors.toSet());
    }

    /**
     * Closes every open stream whose session has ended.
     *
     * @param ended whether a stream's session has ended
     */
    public void closeSessions(Predicate<StreamSession> ended) {
        for (Subscription subscription : subscriptions) {
            if (subscription.session != null && ended.test(subscription.session)) {
                subscription.end();
            }
        }
    }

    /**
     * The login session a stream was opened under: what the session sweep checks it against.
     *
     * @param sessionId the token's session
     * @param clientId  the token's client
     * @param expiresAt the token's expiry
     */
    public record StreamSession(UUID sessionId, UUID clientId, Instant expiresAt) {
    }

    /**
     * One SSE subscriber: its symbol filter, the latest unsent price per symbol,
     * and whether a
     * sender is currently draining those to the client.
     */
    private final class Subscription {
        private final SseEmitter emitter;
        private final Set<String> symbolFilter;
        private final Map<String, PriceState> unsent = new ConcurrentHashMap<>();
        private final AtomicBoolean draining = new AtomicBoolean();
        private volatile boolean closed;
        private final StreamSession session;

        private Subscription(SseEmitter emitter, Set<String> symbolFilter, StreamSession session) {
            this.emitter = emitter;
            this.symbolFilter = symbolFilter;
            this.session = session;
        }

        /**
         * Checks whether a symbol should be sent to this subscriber.
         * 
         * @param symbol the instrument symbol of the tick being broadcast
         * @return true if this subscriber's filter is empty or contains the symbol
         */
        boolean matches(String symbol) {
            return symbolFilter.isEmpty() || symbolFilter.contains(symbol);
        }

        /**
         * Records the latest price for its symbol, replacing any not yet sent, and
         * starts a
         * sender if none is running.
         */
        void offer(PriceState state) {
            if (closed) {
                return;
            }
            unsent.put(state.symbol(), state);
            if (draining.compareAndSet(false, true)) {
                sender.execute(this::drain);
            }
        }

        /**
         * Sends unsent prices until there are none left. Only one drain runs per
         * subscriber at a
         * time, so the client sees one ordered stream.
         */
        private void drain() {
            try {
                while (!closed) {
                    for (String symbol : unsent.keySet()) {
                        PriceState state = unsent.remove(symbol);
                        if (state != null) {
                            emitter.send(SseEmitter.event().name("price").data(state));
                        }
                    }
                    draining.set(false);
                    // A price offered after the loop above but before draining was cleared saw a
                    // drain in progress and didn't start one, so pick it up here.
                    if (unsent.isEmpty() || !draining.compareAndSet(false, true)) {
                        return;
                    }
                }
            } catch (IOException | RuntimeException ex) {
                // The client went away (tab closed, or the frontend reconnecting with a new
                // symbol set). Just drop it: the container is already running its own error
                // handling for the request, and completeWithError() from a non-container thread
                // makes Tomcat throw.
                close();
                // A failed send (or completion) already belongs to container error handling.
                // Completing it again can access a destroyed Tomcat AsyncContext.
            }
        }

        /**
         * Ends the stream because its login session has ended.
         */
        void end() {
            close();
            try {
                emitter.complete();
            } catch (RuntimeException ex) {
                // The client went away while this was closing it; the container is already
                // cleaning up the request, so there's nothing left to complete.
            }
        }

        /**
         * Cleans up the subscription when the SSE connection closes or times out
         */
        void close() {
            closed = true;
            unsent.clear();
            subscriptions.remove(this);
        }
    }
}
